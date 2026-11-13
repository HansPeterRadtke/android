#!/usr/bin/env python3
"""Non-action TLS server: deliver a local speech recording, drop socket, and inspect receipt."""
import asyncio
import json
import ssl
import sys
import wave
from pathlib import Path

import websockets


async def main(cert_path, key_path, wav_path, result_path):
    with wave.open(str(wav_path), "rb") as wave_file:
        assert (wave_file.getnchannels(), wave_file.getsampwidth(), wave_file.getframerate()) == (1, 2, 16000)
        pcm = wave_file.readframes(wave_file.getnframes())
    ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_context.load_cert_chain(cert_path, key_path)
    result = {"connections": 0, "receipt_connections": [], "received_types": [], "received_other_actions": [],
              "recording_frames": len(pcm) // 2, "audio_bytes": len(pcm), "first_disconnect": False}
    received = asyncio.Event()
    hello = {"type": "hello", "commit_protocol": 0, "media_protocols": [0], "conversation_id": "fixture",
             "output": "pcm_s16le_16000_mono"}

    async def handler(socket):
        result["connections"] += 1
        index = result["connections"]

        async def read_client_messages():
            async for message in socket:
                if not isinstance(message, str):
                    result["received_other_actions"].append("binary_uplink_forbidden")
                    continue
                value = json.loads(message)
                kind = value.get("type", "")
                result["received_types"].append({"socket": index, "type": kind})
                if kind == "playback_completed":
                    if (value.get("turn_id") == "fixture-turn" and value.get("stream_id") == "fixture-stream"
                        and value.get("generation") == 11):
                        result["receipt_connections"].append(index)
                        received.set()
                    else:
                        result["received_other_actions"].append("wrong_playback_receipt")
                elif kind not in ("client_state", "history_get", "ping"):
                    result["received_other_actions"].append(kind)

        reader = asyncio.create_task(read_client_messages())
        try:
            await socket.send(json.dumps(hello))
            if index == 1:
                await socket.send(json.dumps({"type": "audio", "event": "start", "kind": "assistant",
                    "turn_id": "fixture-turn", "stream_id": "fixture-stream", "generation": 11,
                    "media_protocol": 0}))
                await socket.send(pcm)
                await socket.send(json.dumps({"type": "audio", "event": "end", "kind": "assistant",
                    "turn_id": "fixture-turn", "stream_id": "fixture-stream", "generation": 11}))
                await asyncio.sleep(.65)
                result["first_disconnect"] = True
                await socket.close(code=1012, reason="planned_fixture_disconnect")
            else:
                await reader
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)

    async with websockets.serve(handler, "127.0.0.1", 15486, ssl=ssl_context, max_size=2**22):
        print("ISOLATED_SERVER_READY", flush=True)
        try:
            await asyncio.wait_for(received.wait(), timeout=55)
            await asyncio.sleep(.3)
        except asyncio.TimeoutError:
            result["timeout"] = True
        finally:
            Path(result_path).write_text(json.dumps(result, indent=2) + "\n")
            print("FIXTURE_RESULT", json.dumps(result), flush=True)
    if result["receipt_connections"] != [2] or result["received_other_actions"]:
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main(*sys.argv[1:]))

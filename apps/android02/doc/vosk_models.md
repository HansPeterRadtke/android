| Rank | Model (exact ZIP basename)              |  Size | LibriSpeech WER % | TEDLIUM WER % | Mean WER % |          Other WER | Rough speed class                                                                      |
| ---- | --------------------------------------- | ----: | ----------------: | ------------: | ---------: | -----------------: | -------------------------------------------------------------------------------------- |
| 1    | vosk-model-en-us-0.22                   | 1.8 G |              5.69 |          6.05 |       5.87 | 29.78 (callcenter) | Big/static graph; server-class. ([alphacephei.com][1])                                 |
| 2    | vosk-model-en-us-0.21                   | 1.6 G |              5.43 |          6.42 |       5.93 | 40.63 (callcenter) | Big/static graph; server-class. ([alphacephei.com][1])                                 |
| 3    | vosk-model-en-us-0.42-gigaspeech        | 2.3 G |              5.64 |          6.24 |       5.94 | 30.17 (callcenter) | Big/static graph; “a bit slow”, \~16 GB decode RAM. ([alphacephei.com][1])             |
| 4    | vosk-model-en-us-daanzu-20200905        | 1.0 G |              7.08 |          8.25 |       7.67 |                  — | Big/static graph; server-class. ([alphacephei.com][1])                                 |
| 5    | vosk-model-en-us-0.22-lgraph            | 128 M |              7.82 |          8.20 |       8.01 |                  — | Dynamic “look-ahead” graph; compact, slower decode than static. ([alphacephei.com][1]) |
| 6    | vosk-model-en-us-daanzu-20200905-lgraph | 129 M |              8.20 |          9.28 |       8.74 |                  — | Dynamic look-ahead; compact, slower decode. ([alphacephei.com][1])                     |
| 7    | vosk-model-small-en-us-0.15             |  40 M |              9.85 |         10.38 |      10.12 |                  — | Small/mobile; designed for phones/RPi (real-time on CPU). ([alphacephei.com][1])       |
| 8    | vosk-model-small-en-us-zamia-0.5        |  49 M |             11.55 |         12.64 |      12.10 |                  — | Small/mobile; older research model. ([alphacephei.com][1])                             |
| 9    | vosk-model-en-us-aspire-0.2             | 1.4 G |             13.64 |         12.89 |      13.27 | 33.82 (callcenter) | Big/static; “not very accurate”. ([alphacephei.com][1])                                |
| —    | vosk-model-en-us-librispeech-0.2        | 845 M |               TBD |           TBD |          — |                  — | Big/static; “not very accurate”; no WER listed. ([alphacephei.com][1])                 |

[1]: https://alphacephei.com/vosk/models "VOSK Models"


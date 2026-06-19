package myapp.app.tts;

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * PhonemeConverter for android_02.
 *
 * - Loads CMUdict (ARPABET) from externalFilesDir("models")/cmudict.dict when available.
 * - Converts ARPABET -> Kokoro IPA-ish chars (subset of Kokoro vocab).
 * - For missing words, uses a simple built-in grapheme->IPA fallback.
 */
class PhonemeConverter(private val context: Context) {
    private val phonemeMap = mutableMapOf<String, String>()

    init {
        loadDictionary()
    }

    private fun loadDictionary() {
        try {
            val modelsDir = context.getExternalFilesDir("models")
            if (modelsDir == null) {
                println("PhonemeConverter: models dir is null; cannot load cmudict.dict")
                return
            }

            val dictFile = File(modelsDir, "cmudict.dict")
            if (!dictFile.isFile || dictFile.length() <= 0L) {
                println("PhonemeConverter: cmudict.dict not found at: ${dictFile.absolutePath} (using fallback)")
                return
            }

            dictFile.bufferedReader().useLines { lines ->
                lines
                    .filter { it.isNotBlank() && !it.startsWith(";;;") }
                    .forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size < 2) return@forEach

                        val rawWord = parts[0]
                        // Strip variant suffix, e.g. WORD(1) -> WORD.
                        val baseWord = rawWord.replace(Regex("\\(\\d+\\)$"), "")
                        if (baseWord.isEmpty()) return@forEach

                        // Keep first pronunciation only (good enough for now).
                        if (phonemeMap.containsKey(baseWord)) return@forEach

                        val phones = parts.subList(1, parts.size)
                        val ipa = arpabetPhonesToIpa(phones)
                        if (ipa.isNotEmpty()) {
                            phonemeMap[baseWord] = ipa
                        }
                    }
            }
            println("PhonemeConverter: cmudict loaded, entries=${phonemeMap.size}")
        } catch (e: IOException) {
            println("PhonemeConverter: error loading dictionary: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            println("PhonemeConverter: error loading cmudict: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun convertToPhonemes(word: String): String {
        // Keep punctuation as-is
        if (word.matches(Regex("[^a-zA-Z']+"))) {
            return word
        }

        // CMU keys are uppercase, keep apostrophes.
        val cleanWord = word.replace(Regex("[^a-zA-Z']"), "").uppercase()
        if (cleanWord.isEmpty()) return word

        val dictHit = phonemeMap[cleanWord]
        if (dictHit != null) {
            return dictHit
        }

        // Fallback: rough grapheme→IPA mapping
        return fallbackTranscribe(word)
    }

    private fun arpabetPhonesToIpa(phones: List<String>): String {
        val out = StringBuilder()
        for (p in phones) {
            if (p.isEmpty()) continue

            val m = Regex("^([A-Z]+)([0-2])?$").matchEntire(p) ?: continue
            val base = m.groupValues[1]
            val stress = m.groupValues.getOrNull(2) ?: ""

            val stressMark = when (stress) {
                "1" -> "ˈ"
                "2" -> "ˌ"
                else -> ""
            }

            val ipa = when (base) {
                // Vowels
                "AA" -> "ɑ"
                "AE" -> "æ"
                "AH" -> if (stress == "0") "ə" else "ʌ"
                "AO" -> "ɔ"
                "AW" -> "aʊ"
                "AY" -> "aɪ"
                "EH" -> "ɛ"
                "ER" -> if (stress == "0") "ɚ" else "ɜ"
                "EY" -> "eɪ"
                "IH" -> "ɪ"
                "IY" -> "i"
                "OW" -> "oʊ"
                "OY" -> "ɔɪ"
                "UH" -> "ʊ"
                "UW" -> "u"

                // Consonants
                "B" -> "b"
                "CH" -> "ʧ"
                "D" -> "d"
                "DH" -> "ð"
                "F" -> "f"
                "G" -> "ɡ"
                "HH" -> "h"
                "JH" -> "ʤ"
                "K" -> "k"
                "L" -> "l"
                "M" -> "m"
                "N" -> "n"
                "NG" -> "ŋ"
                "P" -> "p"
                "R" -> "ɹ"
                "S" -> "s"
                "SH" -> "ʃ"
                "T" -> "t"
                "TH" -> "θ"
                "V" -> "v"
                "W" -> "w"
                "Y" -> "j"
                "Z" -> "z"
                "ZH" -> "ʒ"

                else -> ""
            }

            if (ipa.isEmpty()) continue
            if (stressMark.isNotEmpty()) out.append(stressMark)
            out.append(ipa)
        }
        return out.toString()
    }

    /**
     * Very simple built-in fallback.
     * Not perfect, but better than raw letters and avoids external deps.
     */
    private fun fallbackTranscribe(word: String): String {
        val w = word.lowercase()
        val out = StringBuilder()

        var i = 0
        while (i < w.length) {
            val c = w[i]

            // Basic digraphs first
            if (i + 1 < w.length) {
                val two = w.substring(i, i + 2)
                when (two) {
                    "ch" -> { out.append("tʃ"); i += 2; continue }
                    "sh" -> { out.append("ʃ");  i += 2; continue }
                    "th" -> { out.append("θ");  i += 2; continue }
                    "ph" -> { out.append("f");  i += 2; continue }
                    "ng" -> { out.append("ŋ");  i += 2; continue }
                }
            }

            // Single letters
            val ipa = when (c) {
                'a' -> "æ"
                'b' -> "b"
                'c' -> "k"
                'd' -> "d"
                'e' -> "ɛ"
                'f' -> "f"
                'g' -> "ɡ"
                'h' -> "h"
                'i' -> "ɪ"
                'j' -> "dʒ"
                'k' -> "k"
                'l' -> "l"
                'm' -> "m"
                'n' -> "n"
                'o' -> "ɒ"
                'p' -> "p"
                'q' -> "k"
                'r' -> "ɹ"
                's' -> "s"
                't' -> "t"
                'u' -> "ʊ"
                'v' -> "v"
                'w' -> "w"
                'x' -> "ks"
                'y' -> "j"
                'z' -> "z"
                else -> c.toString() // keep punctuation / digits
            }
            out.append(ipa)
            i++
        }

        return out.toString()
    }

    fun phonemize(text: String, lang: String = "en-us", norm: Boolean = true): String {
        val normalized = if (norm) normalizeText(text) else text
        println("PhonemeConverter.phonemize: normalized=\"$normalized\"")

        // Tokenize words (keeping contractions like "I'm" intact) and punctuation.
        val tokens = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[^A-Za-z\\s]+")
            .findAll(normalized)
            .map { it.value }
            .toList()

        val result = StringBuilder()
        var prevWasWord = false
        for (token in tokens) {
            val isWord = token.any { it.isLetter() }
            if (isWord) {
                if (prevWasWord) result.append(" ")
                val tmp = convertToPhonemes(token)
                    .replace(" ", "")
                val ipa = adjustStressMarkers(tmp)
                result.append(ipa)
                prevWasWord = true
            } else {
                // Punctuation: append directly; add a pause after sentence punctuation.
                result.append(token)
                if (token == "." || token == "!" || token == "?" || token == ":" || token == ";") {
                    result.append(" ")
                }
                prevWasWord = false
            }
        }

        return postProcessPhonemes(result.toString(), lang)
    }

    fun adjustStressMarkers(input: String): String {
        val vowels = setOf(
            'a','e','i','o','u',
            'ɑ','ɐ','ɔ','æ','ɒ','ə','ɨ','ɯ','ɛ','œ','ɝ','ɞ','ɪ','ʊ','ʌ'
        )

        val builder = StringBuilder(input)
        var i = 0

        while (i < builder.length) {
            if (builder[i] == 'ˈ' || builder[i] == 'ˌ') {
                val stressIndex = i
                val stressChar = builder[i]
                for (j in stressIndex + 1 until builder.length) {
                    if (builder[j] in vowels) {
                        builder.deleteCharAt(stressIndex)
                        builder.insert(j - 1, stressChar)
                        i = j
                        break
                    }
                }
            }
            i++
        }

        return builder.toString()
    }

    private fun normalizeText(text: String): String {
        var normalized = text
            .lines()
            .joinToString("\n") { it.trim() }
            .replace("[‘’]".toRegex(), "'")
            .replace("[“”«»]".toRegex(), "\"")
            .replace("[、。？！：；]".toRegex()) { match ->
                when (match.value) {
                    "、" -> ","
                    "。" -> "."
                    "？" -> "?"
                    "！" -> "!"
                    "：" -> ":"
                    "；" -> ";"
                    else -> match.value
                } + " "
            }

        normalized = normalized
            .replace(Regex("\\bD[Rr]\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))"), "Mister")
            .replace(Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))"), "Miss")
            .replace(Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))"), "Mrs")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etc")

        normalized = normalized.replace(Regex("(?<=\\d),(?=\\d)"), "")
        normalized = normalized.replace(Regex("(?<=\\d)-(?=\\d)"), " to ")

        return normalized.trim()
    }

    private fun postProcessPhonemes(phonemes: String, lang: String): String {
        var result = phonemes
            .replace("r", "ɹ")

        // Kokoro-specific fixes (kept from demo)
        result = result.replace("kəkˈoɹoʊ", "kˈoʊkəɹoʊ")
            .replace("kəkˈɔɹəʊ", "kˈəʊkəɹəʊ")

        if (lang == "en-us") {
            result = result.replace("ti", "di")
        }

        return result.trim()
    }
}

package io.wingpane.uc

data class Edit(val deleteBefore: Int, val insert: String)
enum class ToneStyle { New, Old }

/** A word-local Telex renderer. The editor holds only rendered text, never raw keys. */
class TelexComposer(var style: ToneStyle = ToneStyle.New) {
    private val raw = StringBuilder()
    private var rendered = ""

    fun onChar(c: Char): Edit {
        raw.append(c)
        return update()
    }

    fun onBackspace(): Edit? {
        if (raw.isEmpty()) return null
        raw.deleteCharAt(raw.lastIndex)
        return update()
    }

    fun commit() { raw.clear(); rendered = "" }
    fun reset() = commit()
    fun text(): String = rendered

    private fun update(): Edit {
        val next = render(raw.toString())
        val prefix = rendered.commonPrefixWith(next).length
        val edit = Edit(rendered.length - prefix, next.substring(prefix))
        rendered = next
        return edit
    }

    private data class Letter(var value: Char, var form: Char = ' ', var trigger: Char = ' ')

    private fun render(input: String): String {
        val letters = mutableListOf<Letter>()
        var tone = ' '
        var toneTrigger = ' '
        for (c in input) {
            val lower = c.lowercaseChar()
            val previous = letters.lastOrNull()
            if (lower in "sfrxj" && letters.any { it.value.lowercaseChar() in "aeiouy" }) {
                if (toneTrigger == lower) {
                    tone = ' '; toneTrigger = ' '
                    letters.add(Letter(c))
                } else {
                    tone = lower; toneTrigger = lower
                }
                continue
            }
            if (lower == 'z' && (tone != ' ' || letters.any { it.form != ' ' })) {
                tone = ' '; toneTrigger = ' '
                letters.forEach { it.form = ' '; it.trigger = ' ' }
                continue
            }
            if (previous != null) {
                val base = previous.value.lowercaseChar()
                if (lower == 'w' && letters.size >= 2 &&
                    letters[letters.lastIndex - 1].value.lowercaseChar() == 'u' && base == 'o' &&
                    letters[letters.lastIndex - 1].form == ' ' && previous.form == ' ') {
                    letters[letters.lastIndex - 1].form = 'ư'
                    previous.form = 'ơ'
                    continue
                }
                val form = when {
                    lower == base && lower in "aeo" -> lower
                    lower == 'w' && base in "aou" -> lower
                    else -> ' '
                }
                if (form != ' ') {
                    if (previous.trigger == form) {
                        previous.form = ' '; previous.trigger = ' '
                        letters.add(Letter(c))
                    } else if (previous.form == ' ') {
                        previous.form = when (base) {
                            'a' -> if (lower == 'w') 'ă' else 'â'
                            'e' -> 'ê'
                            'o' -> if (lower == 'w') 'ơ' else 'ô'
                            else -> 'ư'
                        }
                        previous.trigger = form
                    } else letters.add(Letter(c))
                    continue
                }
                if (lower == 'd' && base == 'd') {
                    if (previous.form == 'đ') { previous.form = ' '; letters.add(Letter(c)) }
                    else { previous.form = 'đ'; previous.trigger = 'd' }
                    continue
                }
            }
            if (lower == 'w' && letters.isEmpty()) letters.add(Letter(if (c.isUpperCase()) 'Ư' else 'ư'))
            else letters.add(Letter(c))
        }
        val vowelIndices = letters.indices.filter { letters[it].value.lowercaseChar() in "aeiouy" }
            .filterNot { index ->
                index == 1 && letters.size > 2 &&
                    ((letters[0].value.lowercaseChar() == 'g' && letters[index].value.lowercaseChar() == 'i') ||
                        (letters[0].value.lowercaseChar() == 'q' && letters[index].value.lowercaseChar() == 'u')) &&
                    letters.drop(2).any { it.value.lowercaseChar() in "aeiouy" }
            }
        val target = if (tone == ' ') null else chooseTone(letters, vowelIndices)
        return buildString {
            letters.forEachIndexed { index, letter ->
                var out = if (letter.form == ' ') letter.value else if (letter.value.isUpperCase()) letter.form.uppercaseChar() else letter.form
                if (index == target) out = toned(out, tone)
                append(out)
            }
        }
    }

    private fun chooseTone(letters: List<Letter>, vowels: List<Int>): Int? {
        if (vowels.isEmpty()) return null
        vowels.firstOrNull { letters[it].form != ' ' }?.let { marked ->
            if (letters[marked].form != 'ư' || vowels.none { letters[it].form == 'ơ' })
                return vowels.lastOrNull { letters[it].form != ' ' } ?: marked
        }
        if (vowels.size == 1) return vowels[0]
        val pair = vowels.takeLast(2).map { letters[it].value.lowercaseChar() }.joinToString("")
        val last = vowels.last()
        if (last != letters.lastIndex) return last
        if (pair in listOf("oa", "oe", "uy")) return if (style == ToneStyle.New) last else vowels[vowels.size - 2]
        // Open three-vowel clusters (oai, oay, uya, oeo) take the tone on the middle vowel.
        return if (vowels.size == 2) vowels[0] else vowels[vowels.size - 2]
    }

    private fun toned(c: Char, tone: Char): Char {
        val bases = "aăâeêioôơuưy"
        val variants = when (tone) {
            's' -> "áắấéếíóốớúứý"
            'f' -> "àằầèềìòồờùừỳ"
            'r' -> "ảẳẩẻểỉỏổởủửỷ"
            'x' -> "ãẵẫẽễĩõỗỡũữỹ"
            'j' -> "ạặậẹệịọộợụựỵ"
            else -> return c
        }
        val index = bases.indexOf(c.lowercaseChar())
        if (index < 0) return c
        return variants[index].let { if (c.isUpperCase()) it.uppercaseChar() else it }
    }
}

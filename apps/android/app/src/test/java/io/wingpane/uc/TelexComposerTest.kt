package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class TelexComposerTest {
    private fun compose(raw: String, style: ToneStyle = ToneStyle.New): String =
        TelexComposer(style).also { composer -> raw.forEach { composer.onChar(it) } }.text()

    @Test fun wordTable() {
        val cases = listOf(
            "aa" to "â", "aw" to "ă", "ee" to "ê", "oo" to "ô", "ow" to "ơ",
            "uw" to "ư", "dd" to "đ", "w" to "ư", "uow" to "ươ",
            "as" to "á", "af" to "à", "ar" to "ả", "ax" to "ã", "aj" to "ạ",
            "aws" to "ắ", "awf" to "ằ", "awr" to "ẳ", "awx" to "ẵ", "awj" to "ặ",
            "aas" to "ấ", "aaf" to "ầ", "aar" to "ẩ", "aax" to "ẫ", "aaj" to "ậ",
            "ees" to "ế", "eef" to "ề", "eer" to "ể", "eex" to "ễ", "eej" to "ệ",
            "oos" to "ố", "oof" to "ồ", "oor" to "ổ", "oox" to "ỗ", "ooj" to "ộ",
            "ows" to "ớ", "owf" to "ờ", "owr" to "ở", "owx" to "ỡ", "owj" to "ợ",
            "uws" to "ứ", "uwf" to "ừ", "uwr" to "ử", "uwx" to "ữ", "uwj" to "ự",
            "giaf" to "già", "quaf" to "quà", "ass" to "as", "asz" to "a",
            "hoaf" to "hoà", "uys" to "uý", "DDuwowcj" to "Được",
            "Tieengs" to "Tiếng", "Vieetj" to "Việt", "cos" to "có",
            "daaus" to "dấu", "dduwowcj" to "được", "khoong" to "không"
        )
        assertTrue(cases.size >= 40)
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, compose(raw)) }
    }

    @Test fun acceptanceSentence() {
        val composer = TelexComposer()
        val result = buildString {
            "Tieengs Vieetj cos daaus dduwowcj khoong".forEach { c ->
                if (c == ' ') { composer.commit(); append(' ') }
                else {
                    val edit = composer.onChar(c)
                    delete(length - edit.deleteBefore, length)
                    append(edit.insert)
                }
            }
        }
        assertEquals("Tiếng Việt có dấu được không", result)
    }

    @Test fun oldAndNewTonePositions() {
        assertEquals("hoà", compose("hoaf", ToneStyle.New))
        assertEquals("hòa", compose("hoaf", ToneStyle.Old))
        assertEquals("khoẻ", compose("khoer", ToneStyle.New))
        assertEquals("khỏe", compose("khoer", ToneStyle.Old))
        assertEquals("uý", compose("uys", ToneStyle.New))
        assertEquals("úy", compose("uys", ToneStyle.Old))
        assertEquals("toán", compose("toans", ToneStyle.New))
        assertEquals("toán", compose("toans", ToneStyle.Old))
    }

    @Test fun openAndClosedSyllableTonePositions() {
        val cases = listOf(
            "ngoaif" to "ngoài", "xoays" to "xoáy", "hoaij" to "hoại", "ngoeos" to "ngoéo",
            "kiaf" to "kìa", "chiaf" to "chìa", "cuar" to "của", "muaf" to "mùa", "luas" to "lúa",
            "muwa" to "mưa", "muwaf" to "mừa", "cais" to "cái", "caos" to "cáo",
            "saus" to "sáu", "mays" to "máy", "keos" to "kéo", "xius" to "xíu",
            "toois" to "tối", "tuis" to "túi",
            "cuwus" to "cứu", "muoons" to "muốn", "nguwowif" to "người",
            "thuyeenf" to "thuyền", "giuwax" to "giữa", "quoocs" to "quốc"
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, compose(raw)) }
    }

    @Test fun backspaceWithinWordReplaysRawInput() {
        val c = TelexComposer()
        "Tieengs".forEach { c.onChar(it) }
        assertEquals("Tiếng", c.text())
        assertEquals(Edit(3, "êng"), c.onBackspace())
        assertEquals("Tiêng", c.text())
        c.onBackspace()
        assertEquals("Tiên", c.text())
    }

    @Test fun boundariesAndEmptyBackspace() {
        val c = TelexComposer()
        assertNull(c.onBackspace())
        c.onChar('a')
        c.commit()
        assertNull(c.onBackspace())
        c.onChar('a')
        assertEquals("a", c.text())
        c.reset()
        assertEquals("", c.text())
    }
}

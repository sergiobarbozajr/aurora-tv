package com.auroratv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Paleta tirada de cada imagem de fundo: destaque, cartões, painéis e vidro do menu combinam com o tema. */
class Pal(
    val accent: Color, val card: Color, val cardHi: Color, val panel: Color, val glass: Color,
    // textos secundários (legendas, dicas): claros e levemente tingidos pelo tema, sempre legíveis
    val light: Color = Color(0xFFDCE6F5), val dim: Color = Color(0xFFB9C6DA)
)

val THEME_PALS = listOf(
    Pal(Color(0xFF6CC6FF), Color(0xFF1A1F2C), Color(0xFF232A3B), Color(0xFF161A24), Color(0x6B0E121C)), // Galáxia
    Pal(Color(0xFF9DB8FF), Color(0xFF1B1E2E), Color(0xFF262A3E), Color(0xFF171A27), Color(0x6B10131F)), // Planetas
    Pal(Color(0xFFFFC86E), Color(0xFF1E1C24), Color(0xFF2A2732), Color(0xFF19171E), Color(0x6B141218), Color(0xFFF3EADB), Color(0xFFD6C8B3)), // Órbitas
    Pal(Color(0xFF7FE3E0), Color(0xFF14242A), Color(0xFF1C3138), Color(0xFF111E23), Color(0x700B171B)), // Mar alienígena
    Pal(Color(0xFFFFB067), Color(0xFF2A1E16), Color(0xFF38281D), Color(0xFF231912), Color(0x7A1A120C), Color(0xFFFBEBDD), Color(0xFFE4CDB5)), // Deserto
    Pal(Color(0xFFA9D4FF), Color(0xFF1B222C), Color(0xFF26303D), Color(0xFF161C24), Color(0x700F151C)), // Plutão gelado
    Pal(Color(0xFFFFE3A8), Color(0xFF222121), Color(0xFF2E2D2C), Color(0xFF1C1B1B), Color(0x70141313), Color(0xFFF2EDE6), Color(0xFFD2CBC2)), // Cratera ao sol
    Pal(Color(0xFFFF9E7A), Color(0xFF2E1D16), Color(0xFF3B2820), Color(0xFF261811), Color(0x801E120C), Color(0xFFFBE8E0), Color(0xFFE6C9BC)), // Marte
    Pal(Color(0xFFFFB357), Color(0xFF25160B), Color(0xFF33200F), Color(0xFF1F1209), Color(0x78180D05), Color(0xFFFBEBD6), Color(0xFFE5CBA6)), // Tempestade em Júpiter
    Pal(Color(0xFFFF7A2E), Color(0xFF26170C), Color(0xFF34200F), Color(0xFF1F1309), Color(0x80180E06), Color(0xFFFBE7D3), Color(0xFFE6C6A3)), // Vênus vulcânico
    Pal(Color(0xFF6FB6FF), Color(0xFF1C1E22), Color(0xFF282B30), Color(0xFF17191C), Color(0x70101215))  // Terra vista da Lua
)

object ThemeState {
    var pal by mutableStateOf(THEME_PALS[0])
    fun set(i: Int) {
        pal = THEME_PALS[i.coerceIn(0, THEME_PALS.size - 1)]
    }
}

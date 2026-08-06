package com.joysong.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary

data class CountryCode(
    val flag: String,
    val name: String,
    val dialCode: String
)

val CountryCodeList = listOf(
    CountryCode("🇨🇳", "中国大陆", "+86"),
    CountryCode("🇭🇰", "中国香港", "+852"),
    CountryCode("🇲🇴", "中国澳门", "+853"),
    CountryCode("🇹🇼", "中国台湾", "+886"),
    CountryCode("🇺🇸", "美国", "+1"),
    CountryCode("🇯🇵", "日本", "+81"),
    CountryCode("🇰🇷", "韩国", "+82")
)

@Composable
fun CountryCodePicker(
    selectedCode: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = CountryCodeList.find { it.dialCode == selectedCode } ?: CountryCodeList[0]

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant.copy(alpha = 0.3f))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected.flag,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = selected.dialCode,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = TextHint
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CountryCodeList.forEach { country ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = country.flag, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = country.name, fontSize = 14.sp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = country.dialCode, fontSize = 14.sp, color = TextHint)
                        }
                    },
                    onClick = {
                        onCodeChange(country.dialCode)
                        expanded = false
                    }
                )
            }
        }
    }
}

package app.tvapps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** 7.0: общий «глазик» для скрытых полей (токены, ключи API, код сопряжения): справа в полосе поля, цвет акцента, по умолчанию значение скрыто. */
@Composable
fun SecretEye(shown: Boolean, tint: Color, onToggle: () -> Unit) {
    Icon(
        if (shown) HIcons.eyeOff else HIcons.eye, stringResource(R.string.pass_show), tint = tint,
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { onToggle() }.padding(5.dp),
    )
}

fun secretTransform(shown: Boolean): VisualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation()

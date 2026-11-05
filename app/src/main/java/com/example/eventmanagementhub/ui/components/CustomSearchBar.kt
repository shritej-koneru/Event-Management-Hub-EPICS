package com.example.eventmanagementhub.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.ui.theme.Accent
import com.example.eventmanagementhub.ui.theme.Ink3

/** Bordered search input: Surface, LineStrong border, Accent when focused, 6dp radius. */
@Composable
fun CustomSearchBar(
    value: String = "",
    onValueChange: (String) -> Unit = {},
    placeholder: String = "Search events, venues or categories",
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag("search-bar"),
        placeholder = {
            Text(text = placeholder, style = MaterialTheme.typography.bodyMedium, color = Ink3, maxLines = 1)
        },
        singleLine = true,
        shape = ControlShape,
        textStyle = MaterialTheme.typography.bodyMedium,
        // The soft keyboard's Search key runs the query too.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
        colors = hubFieldColors(),
        trailingIcon = {
            IconButton(
                onClick = { onSearch(value) },
                modifier = Modifier.testTag("search-submit")
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Accent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}

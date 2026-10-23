package com.iaandroid17.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DocsScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addDocuments(uris)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Документы", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Поддерживаются: TXT, MD, CSV, JSON, XML, HTML, код, PDF с текстовым слоем, DOCX, PPTX, XLSX (текстовые ячейки), ODT. " +
                        "Текст извлекается на телефоне и хранится в памяти приложения. Приложение видит только выбранные вами файлы. " +
                        "Сканы и изображения пока не распознаются (OCR — следующий этап).",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать файлы…") }
                if (vm.busyMessage.isNotBlank()) {
                    Text(vm.busyMessage, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (vm.banner.isNotBlank()) Text(vm.banner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (vm.docList.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.clearDocs() }) { Text("Удалить все документы") }
                } else {
                    Text("Пока нет документов.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(vm.docList, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Medium)
                        Text(
                            "${d.chars} символов · ${d.chunks} фрагм." + if (d.note.isNotBlank()) " · ${d.note}" else "",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(checked = d.enabled, onCheckedChange = { vm.setDocEnabled(d.id, it) })
                    TextButton(onClick = { vm.removeDoc(d.id) }) { Text("✕") }
                }
            }
        }
    }
}

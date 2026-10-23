package com.iaandroid17

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { MainUi() } } }
}
private data class Line(val user: Boolean, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainUi() {
 var tab by remember { mutableIntStateOf(0) }
 var entry by remember { mutableStateOf("") }
 var profile by remember { mutableStateOf("Ограниченный") }
 var contextLimit by remember { mutableFloatStateOf(4096f) }
 var picked by remember { mutableStateOf("Файл не выбран") }
 var explain by remember { mutableStateOf(false) }
 val conversation = remember { mutableStateListOf(Line(false, "Добро пожаловать! Интерфейс готов к проверке. Локальная модель пока не подключена.")) }
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) picked = uri.lastPathSegment ?: "Документ выбран" }
 Scaffold(topBar={ TopAppBar(title={ Column { Text("IA-Android-17", fontWeight=FontWeight.Bold); Text("Локальный помощник · ранняя версия", style=MaterialTheme.typography.labelSmall) } }, actions={ TextButton(onClick={tab=1}) { Text("Настройки") } }) }, bottomBar={ NavigationBar { NavigationBarItem(selected=tab==0,onClick={tab=0},icon={},label={Text("Помощник")}); NavigationBarItem(selected=tab==1,onClick={tab=1},icon={},label={Text("Настройки")}) } }) { inset ->
  if(tab==0) Column(Modifier.fillMaxSize().padding(inset).padding(16.dp)) {
   Card { Column(Modifier.padding(12.dp)) { Text("Модель не подключена", fontWeight=FontWeight.Bold); Text("Это рабочая оболочка. Ответы пока демонстрационные."); TextButton(onClick={tab=1}) { Text("Настроить") } } }
   Text(picked, style=MaterialTheme.typography.bodySmall)
   LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) { items(conversation) { item -> Card(Modifier.fillMaxWidth()) { Text((if(item.user) "Вы: " else "IA: ")+item.body, Modifier.padding(12.dp)) } } }
   Row(verticalAlignment=Alignment.CenterVertically) {
    OutlinedButton(onClick={picker.launch(arrayOf("*/*"))}) { Text("Файл") }
    Spacer(Modifier.width(6.dp))
    OutlinedTextField(value=entry,onValueChange={entry=it},modifier=Modifier.weight(1f),placeholder={Text("Сообщение")},maxLines=3)
    Spacer(Modifier.width(4.dp))
    Button(onClick={if(entry.isNotBlank()){conversation.add(Line(true,entry.trim()));conversation.add(Line(false,"Сообщение показано в прототипе. Для ответа подключим модель."));entry=""}}) { Text("➤") }
   }
  } else LazyColumn(Modifier.fillMaxSize().padding(inset).padding(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
   item { Text("Настройки",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold); Text("Параметры пока демонстрационные: inference не подключён.") }
   item { Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("Основная модель",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Text("PF-005 · Qwen3-8B Q4_K_M"); Text("Модель планируется отдельно от APK. Runtime ещё предстоит интегрировать.") } } }
   item { Card { Column(Modifier.padding(16.dp)) { Text("Профиль разрешений",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); listOf("Ограниченный","Расширенный","Максимально доступный").forEach { p -> Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=profile==p,onClick={profile=p}); Text(p) } }; Text("Выбор не выдаёт разрешения Android. Защищённые данные приложений недоступны.",style=MaterialTheme.typography.bodySmall) } } }
   item { Card { Column(Modifier.padding(16.dp)) { Text("Лимит контекста",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Text(contextLimit.toInt().toString()+" токенов"); Slider(value=contextLimit,onValueChange={contextLimit=it},valueRange=2048f..16384f,steps=6); Text("Больше токенов — выше расход RAM. Пока настройка не применяется.",style=MaterialTheme.typography.bodySmall) } } }
   item { Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Документы",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Button(onClick={picker.launch(arrayOf("*/*"))}) { Text("Выбрать файл") }; Text(picked); Text("Пока выбирается только файл; извлечение содержимого будет добавлено позже.",style=MaterialTheme.typography.bodySmall) } } }
   item { Card { Column(Modifier.padding(16.dp)) { Text("Приватность",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Text("• Не запрашиваем широкие права.\n• Файл выбирает пользователь.\n• Документы считаются недоверенными.\n• Важные операции будут требовать подтверждения."); TextButton(onClick={explain=true}) { Text("О локальной обработке") } } } }
   item { OutlinedButton(onClick={tab=0},modifier=Modifier.fillMaxWidth()) { Text("К помощнику") } }
  }
 }
 if(explain) AlertDialog(onDismissRequest={explain=false},title={Text("Локальная обработка")},text={Text("Цель — запуск модели на телефоне. Сейчас inference не интегрирован. Для полноценной работы понадобятся движок, совместимые веса модели и тестирование памяти/нагрева.")},confirmButton={TextButton(onClick={explain=false}) { Text("Понятно") }})
}

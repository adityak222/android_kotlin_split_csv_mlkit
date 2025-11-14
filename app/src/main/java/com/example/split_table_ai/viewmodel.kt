package com.example.split_table_ai


import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope



import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive



class MainViewModel : ViewModel() {

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val _rawMessages = MutableStateFlow<List<MessageData>>(emptyList())
    val rawMessages: StateFlow<List<MessageData>> = _rawMessages

    private val _processedMessages = MutableStateFlow<List<MessageData>>(emptyList())
    val processedMessages: StateFlow<List<MessageData>> = _processedMessages

    private val _selectedColumns = MutableStateFlow<List<String>>(emptyList())
    val selectedColumns: StateFlow<List<String>> = _selectedColumns

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting

    private val _isPollinationLoading = MutableStateFlow(false)
    val isPollinationLoading: StateFlow<Boolean> = _isPollinationLoading

    private var loadedFileName: String? = null


    private var originalFileUri: Uri? = null


    fun loadCsv(context: Context, uri: Uri) {
        val parsed = parseCsvFile(context, uri)
        _rawMessages.value = parsed
        _processedMessages.value = parsed

        originalFileUri = uri // Save the original URI
    }



    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "exported_data.csv" // fallback name
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }


    fun setSelectedColumns(columns: List<String>) {
        _selectedColumns.value = columns
    }

    fun extractEntities(context: Context) {
        if (_rawMessages.value.isEmpty() || _selectedColumns.value.isEmpty()) return

        _isExtracting.value = true

        extractEntitiesFromMessages(context, _rawMessages.value, _selectedColumns.value) { updated ->
            val extended = processDetectedEntitiesAndExtendTable(updated)
            _processedMessages.value = extended
            _isExtracting.value = false
        }
    }

    fun export(context: Context) {


        exportMessagesToCsv(context, _processedMessages.value, originalFileUri)
    }

    private val _orderedHeaders = MutableStateFlow<List<String>>(emptyList())
    val orderedHeaders: StateFlow<List<String>> = _orderedHeaders

    fun setOrderedHeaders(headers: List<String>) {
        _orderedHeaders.value = headers
    }

    fun exportMessagesToCsv(context: Context, messages: List<MessageData>, uri: Uri?) {
        if (messages.isEmpty() || uri == null) {
            Toast.makeText(context, "No export location available.", Toast.LENGTH_SHORT).show()
            return
        }

        // Use the stored ordered headers. Fall back to all keys if not set.
        val finalHeaders = if (_orderedHeaders.value.isNotEmpty()) {
            _orderedHeaders.value
        } else {
            messages.flatMap { it.data.keys }.toSet().toList()
        }

        try {
            val outputStream = context.contentResolver.openOutputStream(uri, "wt")
            val writer = OutputStreamWriter(outputStream ?: throw IOException("Unable to open output stream"))
            val csvWriter = CSVWriter(writer)

            csvWriter.writeNext(finalHeaders.toTypedArray())

            messages.forEach { message ->
                // Use finalHeaders here to ensure the order is correct
                val row = finalHeaders.map { header -> message.data[header] ?: "" }.toTypedArray()
                csvWriter.writeNext(row)
            }

            csvWriter.close()
            Log.d("export", "CSV saved to $uri")
            Toast.makeText(context, "CSV saved successfully", Toast.LENGTH_LONG).show()

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    fun parseCsvFile(context: Context, uri: Uri): List<MessageData> {
        val messages = mutableListOf<MessageData>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            val reader = CSVReader(InputStreamReader(inputStream))

            val headers = reader.readNext()?.map { it.trim() } ?: return emptyList()

            var nextLine: Array<String>?
            while (reader.readNext().also { nextLine = it } != null) {
                nextLine?.let { row ->
                    if (row.size == headers.size) {
                        val rowMap = headers.zip(row).associate { (key, value) ->
                            key to value.trim()
                        }.toMutableMap()
                        messages.add(MessageData(data = rowMap))
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("CSVParse", "Error parsing CSV", e)
            // Optionally show a toast to the user
        }
        return messages
    }




    fun extractEntitiesFromMessages(
        context: Context,
        messages: List<MessageData>,
        selectedColumns: List<String>,
        onComplete: (List<MessageData>) -> Unit
    ) {
        val extractor = EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )

        extractor.downloadModelIfNeeded().addOnSuccessListener {
            val updatedMessages = mutableListOf<MessageData>()
            var index = 0

            fun next() {
                if (index >= messages.size) {
                    onComplete(updatedMessages)
                    return
                }

                val originalMessage = messages[index]

                // Combine selected columns
                val combinedText = selectedColumns.joinToString(" ") { header ->
                    originalMessage.data[header] ?: ""
                }.replace("-", " ")

                val params = EntityExtractionParams.Builder(combinedText).build()

                extractor.annotate(params)
                    .addOnSuccessListener { annotations ->
                        val entities: List<Pair<String, String>> = annotations.flatMap { annotation ->
                            annotation.entities.mapNotNull { entity ->
                                val text = annotation.annotatedText

                                val type: String? = when (entity.type) {
                                    Entity.TYPE_ADDRESS -> "Address"
                                    Entity.TYPE_DATE_TIME -> {
                                        val resolved = resolveDateTimeText(text)
                                        return@mapNotNull "Date-Time" to resolved
                                    }
                                    Entity.TYPE_EMAIL -> "Email"
                                    Entity.TYPE_PHONE -> {
                                        if (text.replace(" ", "").length < 10 || !isLikelyPhone(text)) return@mapNotNull null
                                        "Phone"
                                    }
                                    Entity.TYPE_PAYMENT_CARD -> "Card"
                                    Entity.TYPE_TRACKING_NUMBER -> "Tracking"
                                    Entity.TYPE_URL -> "URL"
                                    Entity.TYPE_MONEY -> "Money"
                                    Entity.TYPE_FLIGHT_NUMBER -> "Flight"
                                    Entity.TYPE_IBAN -> "IBAN"
                                    Entity.TYPE_ISBN -> "ISBN-13"
                                    else -> null
                                }

                                type?.let { typeName -> typeName to text }
                            }
                        }

                        updatedMessages.add(originalMessage.copy(detectedEntities = entities))
                        index++
                        next()
                    }
                    .addOnFailureListener {
                        updatedMessages.add(originalMessage.copy(detectedEntities = emptyList()))
                        index++
                        next()
                    }
            }

            next()
        }.addOnFailureListener {
            onComplete(messages) // If model download fails
        }
    }

    fun isLikelyPhone(text: String): Boolean {
        val phoneRegex = Regex("""\+?[0-9][0-9\-\(\)\s]{7,}""") // More than 7 digits
        return phoneRegex.matches(text)

    }
    fun resolveDateTimeText(entityText: String): String {
        val lower = entityText.lowercase()
        val today = java.time.LocalDate.now()

        return when {
            "tomorrow" in lower -> today.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            "today" in lower -> today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            else -> entityText // fallback: keep original if not relative
        }
    }
    fun processDetectedEntitiesAndExtendTable(messages: List<MessageData>): List<MessageData> {
        // Collect original columns from first message
        val detectedTypes = mutableMapOf<String, Boolean>()

        messages.forEach { message ->
            message.detectedEntities.forEach { (type, text) ->
                detectedTypes[type] = true
            }
        }

        detectedTypes.forEach{ (type1, _) ->
            messages.forEach { message ->
                message.data[type1] = ""
            }
        }

        messages.forEach { message ->
            message.detectedEntities.forEach { (type, text) ->
                message.data[type] = text
            }
        }



        return messages
    }

    fun deleteSelectedColumns(columnsToDelete: List<String>) {
        _rawMessages.value = _rawMessages.value.map { msg ->
            msg.copy(data = msg.data.filterKeys { it !in columnsToDelete }.toMutableMap())
        }
        _processedMessages.value = _processedMessages.value.map { msg ->
            msg.copy(data = msg.data.filterKeys { it !in columnsToDelete }.toMutableMap())
        }
        _selectedColumns.value = _selectedColumns.value.filter { it !in columnsToDelete }
        _selectedColumns.value = emptyList()
    }




    private val _selectedRowIndices = mutableStateOf<Set<Int>>(emptySet())
    val selectedRowIndices: State<Set<Int>> = _selectedRowIndices

    fun toggleRowSelection(index: Int) {
        _selectedRowIndices.value = _selectedRowIndices.value.toMutableSet().apply {
            if (contains(index)) remove(index) else add(index)
        }
    }

    fun clearRowSelection() {
        _selectedRowIndices.value = emptySet()
    }

    fun deleteSelectedRows() {
        val indices = _selectedRowIndices.value
        _rawMessages.value = _rawMessages.value.filterIndexed { index, _ -> index !in indices }
        _processedMessages.value = _processedMessages.value.filterIndexed { index, _ -> index !in indices }
        clearRowSelection()
    }


    private val _searchMatches = mutableStateOf<List<Pair<Int, String>>>(emptyList())
    val searchMatches: State<List<Pair<Int, String>>> = _searchMatches

    private val _currentMatchIndex = mutableStateOf(0)
    val currentMatchIndex: State<Int> = _currentMatchIndex

    private var currentSearchText: String = ""

    fun search(query: String) {
        currentSearchText = query.trim()

        val matches = _processedMessages.value.mapIndexedNotNull { rowIndex, message ->
            val matchingCols = _selectedColumns.value.filter { columnKey ->
                message.data[columnKey]?.contains(currentSearchText, ignoreCase = true) == true
            }.map { columnKey -> rowIndex to columnKey }

            if (matchingCols.isNotEmpty()) matchingCols else null
        }.flatten()

        _searchMatches.value = matches
        _currentMatchIndex.value = 0.coerceAtMost(matches.lastIndex)
    }

    fun nextMatch() {
        val next = (_currentMatchIndex.value + 1) % _searchMatches.value.size
        _currentMatchIndex.value = next
    }

    fun previousMatch() {
        val prev = (_currentMatchIndex.value - 1 + _searchMatches.value.size) % _searchMatches.value.size
        _currentMatchIndex.value = prev
    }

    val currentMatch: Pair<Int, String>?
        get() = _searchMatches.value.getOrNull(_currentMatchIndex.value)


    fun replaceCurrentMatch(replacement: String) {
        if (replacement.isEmpty()) return  // ✅ Skip empty replacements

        val match = currentMatch ?: return
        val (rowIndex, columnKey) = match

        val currentList = _processedMessages.value.toMutableList()
        val row = currentList.getOrNull(rowIndex) ?: return

        val newData = row.data.toMutableMap()

        val oldValue = newData[columnKey]
        if (oldValue is String && oldValue.contains(currentSearchText, ignoreCase = true)) {
            val updatedValue = oldValue.replace(currentSearchText, replacement, ignoreCase = true)
            newData[columnKey] = updatedValue
            currentList[rowIndex] = row.copy(data = newData)
            _processedMessages.value = currentList

            // Re-run search to update highlights
            search(currentSearchText)
        }
    }

    fun clearSearch() {
        _searchMatches.value = emptyList()
        _currentMatchIndex.value = 0
        currentSearchText = ""
    }


    fun extractWithPollination(userPrompt: String) {
        if (_selectedColumns.value.isEmpty()) return

        _isPollinationLoading.value = true

        viewModelScope.launch {
            try {
                // 1. Get all selected rows (or use all if none selected)
                val selectedRows = _processedMessages.value.filterIndexed { index, _ ->
                    _selectedRowIndices.value.contains(index)
                }

                val rowsToProcess = if (selectedRows.isEmpty()) {
                    _processedMessages.value
                } else {
                    selectedRows
                }

                // Map each message to its original index *before* processing
                val indexedRowsToProcess = rowsToProcess.mapNotNull { messageData ->
                    val originalIndex = _processedMessages.value.indexOfFirst { it === messageData }
                    if (originalIndex == -1) null else (originalIndex to messageData)
                }

                // 2. Launch all API calls in parallel using async
                val deferredResults = indexedRowsToProcess.map { (originalIndex, messageData) ->
                    async { // Launch a new coroutine for this row
                        try {
                            // 3. Combine selected columns into text for the prompt
                            val dataForPrompt = _selectedColumns.value.joinToString(", ") { column ->
                                "$column: ${messageData.data[column] ?: ""}"
                            }

                            // 4. Create the full prompt
                            val fullPrompt = """
                            User Prompt: "$userPrompt"
                            Data: [$dataForPrompt]
                            
                            Respond ONLY with a flat, single-level JSON object.
                            Example: {"key1": "value1", "key2": "value2"}
                            """.trimIndent()

                            // --- REFACTOR: Revert to GET and add URLEncoder ---
                            // 5. URL-encode the prompt for the GET request
                            // This is required for the GET path
                            val encodedPrompt = URLEncoder.encode(fullPrompt, "UTF-8")

                            // 6. Call the API (GET version)
                            val responseText = PollinationApiClient.api.generateText(prompt = encodedPrompt).string()
                            // --- END REFACTOR ---


                            // 7. Parse the JSON response
                            val parsedJson = jsonParser.decodeFromString<Map<String, JsonElement>>(responseText)
                            val updatedData = messageData.data.toMutableMap()

                            // 8. Iterate through parsed JSON and add/update columns
                            parsedJson.forEach { (key, jsonElement) ->
                                val newColumnName = "AI: $key"
                                val value = when {
                                    jsonElement is JsonPrimitive -> jsonElement.content
                                    else -> jsonElement.toString()
                                }
                                updatedData[newColumnName] = value
                            }

                            originalIndex to messageData.copy(data = updatedData)

                        } catch (e: Exception) {
                            // Fallback if AI response is not valid JSON or network fails
                            Log.e("PollinationParse", "Failed to parse AI JSON for row $originalIndex", e)
                            val updatedData = messageData.data.toMutableMap()
                            val failedColumnName = "AI Prompt (Failed): ${userPrompt.take(20)}..."

                            // Store the *actual error* for debugging
                            updatedData[failedColumnName] = e.toString() // Get the full error message

                            originalIndex to messageData.copy(data = updatedData)
                        }
                    } // end async
                } // end map

                // 9. Wait for all parallel jobs to complete
                val results = deferredResults.awaitAll()

                // 10. Apply all updates to a new list
                val updatedMessages = _processedMessages.value.toMutableList()
                results.forEach { (index, updatedMessage) ->
                    if (index in updatedMessages.indices) {
                        updatedMessages[index] = updatedMessage
                    }
                }

                // 11. Update UI state once with all changes
                _processedMessages.value = updatedMessages

                clearRowSelection()

            } catch (e: Exception) {
                Log.e("PollinationAI", "Failed to extract with Pollination (Global)", e)
            } finally {
                _isPollinationLoading.value = false
            }
        }
    }



}

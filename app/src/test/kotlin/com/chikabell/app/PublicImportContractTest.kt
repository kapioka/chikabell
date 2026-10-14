package com.chikabell.app

import com.chikabell.app.importexport.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PublicImportContractTest {
    private val root: File get() = File(System.getProperty("chikabell.repo.root"))
    @Test fun publishedExampleUsesTheExistingParserAndValidator() {
        val contract = File(root, "docs/ai-import-contract.md").readText()
        val json = contract.substringAfter("```json\n").substringBefore("\n```")
        val parsed = LocationTransferCodec.parseJson(json)
        assertTrue(parsed is TransferParseResult.Success)
        val preview = LocationImportPlanner.preview((parsed as TransferParseResult.Success).records, emptyList(), TransferFormat.JSON)
        assertTrue(preview.errors.toString(), preview.canApply)
    }
    @Test fun schemaVersionAndRequiredFieldsMatchActualEncoding() {
        val schema = Json.parseToJsonElement(File(root, "schemas/location-import-v2.schema.json").readText()).jsonObject
        val properties = schema.getValue("properties").jsonObject
        assertEquals(LocationTransferCodec.SCHEMA_VERSION, properties.getValue("schemaVersion").jsonObject.getValue("const").jsonPrimitive.int)
        val item = properties.getValue("locations").jsonObject.getValue("items").jsonObject
        val required = item.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        val encoded = Json.parseToJsonElement(LocationTransferCodec.encodeImportJson(listOf(LocationTransferRecord(null, "fixture", "", 35.0, 135.0, 300, 60, 720, true, null)))).jsonObject
        assertTrue(encoded.getValue("locations").jsonArray.single().jsonObject.keys.containsAll(required))
    }
    @Test fun publicRouterLinksResolveWithinTheRepository() {
        for (path in listOf("ai/AGENTS.md", "docs/ai-import-contract.md")) {
            val source = File(root, path)
            Regex("\\[[^]]+\\]\\(([^)]+)\\)").findAll(source.readText()).forEach {
                val target = it.groupValues[1]
                if (!target.startsWith("https://")) assertTrue(target, File(source.parentFile, target).isFile)
            }
        }
    }
}

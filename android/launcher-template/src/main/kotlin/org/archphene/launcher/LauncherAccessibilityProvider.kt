package org.archphene.launcher

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bounded Android virtual accessibility tree for one manager-owned Linux app.
 *
 * Tree replacement is event driven. The immutable snapshot is parsed on the
 * Binder callback thread, while framework events are posted to the current
 * launcher SurfaceView. Android actions cross the authenticated session Binder
 * and are consumed by the private AT-SPI translator.
 */
internal class LauncherAccessibilityProvider(
    private val submitAction: (Int, String, String) -> Boolean,
    private val submitMenuFallback: (Int, Int, Boolean) -> Boolean,
) : AccessibilityNodeProvider() {
    private data class Node(
        val id: Int,
        val parent: Int,
        val role: String,
        val text: String,
        val description: String,
        val windowTitle: String,
        val bounds: Rect,
        val flags: Int,
        var children: IntArray = IntArray(0),
    ) {
        fun has(flag: Int): Boolean = flags and flag != 0
    }

    private data class Tree(
        val viewportWidth: Int,
        val viewportHeight: Int,
        val nodes: SparseArray<Node>,
        val ordered: Array<Node>,
    )

    @Volatile private var host: View? = null
    @Volatile private var tree = emptyTree()
    @Volatile private var accessibilityFocus = 0
    @Volatile private var inputFocus = 0
    @Volatile private var viewportTransform: AccessibilityViewportTransform? = null
    private val parseLock = Any()
    private var wireBuffer = ByteArray(INITIAL_TREE_BUFFER_BYTES)
    private var boundsRefreshGeneration = 0

    fun attach(view: View) {
        host = view
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.post {
            if (host === view) {
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
    }

    fun detach(view: View) {
        if (host === view) {
            host = null
        }
    }

    fun clear() {
        tree = emptyTree()
        viewportTransform = null
        accessibilityFocus = 0
        inputFocus = 0
        sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun updateViewportTransform(transform: AccessibilityViewportTransform) {
        viewportTransform = transform
        sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun refreshBoundsAfterTransition() {
        val currentHost = host ?: return
        val generation = ++boundsRefreshGeneration
        var frames = 0
        val refresh =
            object : Runnable {
                override fun run() {
                    if (host !== currentHost || generation != boundsRefreshGeneration) {
                        return
                    }
                    frames++
                    if (frames >= POST_TRANSITION_BOUNDS_FRAMES) {
                        submitAction(0, "refresh", "")
                        sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    } else {
                        currentHost.postOnAnimation(this)
                    }
                }
            }
        currentHost.postOnAnimation(refresh)
    }

    fun publish(descriptor: ParcelFileDescriptor): Boolean =
        runCatching {
            descriptor.use { owned ->
                val stat = Os.fstat(owned.fileDescriptor)
                require(
                    stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
                        stat.st_size in TREE_HEADER_BYTES.toLong()..MAX_TREE_BYTES.toLong(),
                ) {
                    "Accessibility tree descriptor is invalid"
                }
                Os.lseek(owned.fileDescriptor, 0L, OsConstants.SEEK_SET)
                val parsed =
                    synchronized(parseLock) {
                        val size = stat.st_size.toInt()
                        if (wireBuffer.size < size) {
                            wireBuffer = ByteArray(nextBufferSize(size))
                        }
                        ParcelFileDescriptor.AutoCloseInputStream(
                            ParcelFileDescriptor.dup(owned.fileDescriptor),
                        ).use { stream ->
                            var offset = 0
                            while (offset < size) {
                                val read = stream.read(wireBuffer, offset, size - offset)
                                require(read > 0) { "Accessibility tree ended early" }
                                offset += read
                            }
                            require(stream.read() == -1) {
                                "Accessibility tree grew while being read"
                            }
                        }
                        parse(
                            ByteBuffer.wrap(wireBuffer, 0, size)
                                .order(ByteOrder.BIG_ENDIAN),
                        )
                    }
                tree = parsed
                if (parsed.nodes[accessibilityFocus] == null) accessibilityFocus = 0
                if (parsed.nodes[inputFocus] == null) inputFocus = 0
            }
            sendEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Rejected Linux accessibility tree", error)
            false
        }

    fun sendNamedEvent(
        nodeId: Int,
        type: String,
    ): Boolean {
        val event =
            when (type) {
                "focus" -> AccessibilityEvent.TYPE_VIEW_FOCUSED
                "selected" -> AccessibilityEvent.TYPE_VIEW_SELECTED
                "text" -> AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                "clicked" -> AccessibilityEvent.TYPE_VIEW_CLICKED
                "window" -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                "content" -> AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                else -> return false
            }
        if (nodeId != 0 && tree.nodes[nodeId] == null) return false
        if (event == AccessibilityEvent.TYPE_VIEW_FOCUSED) inputFocus = nodeId
        sendEvent(nodeId, event)
        return true
    }

    fun activateMenuFallback(
        nodeId: Int,
        transition: Boolean,
    ): Boolean {
        val currentHost = host ?: return false
        val node = tree.nodes[nodeId] ?: return false
        val bounds = displayBounds(node, currentHost)
        return submitMenuFallback(
            bounds.exactCenterX().roundToInt(),
            bounds.exactCenterY().roundToInt(),
            transition,
        )
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val currentHost = host ?: return null
        val currentTree = tree
        if (virtualViewId == View.NO_ID) {
            return AccessibilityNodeInfo.obtain(currentHost).apply {
                currentHost.onInitializeAccessibilityNodeInfo(this)
                for (node in currentTree.ordered) {
                    if (node.parent == 0) addChild(currentHost, node.id)
                }
            }
        }
        val node = currentTree.nodes[virtualViewId] ?: return null
        return AccessibilityNodeInfo.obtain().apply {
            setSource(currentHost, node.id)
            packageName = currentHost.context.packageName
            className = androidClass(node.role)
            if (node.parent == 0) {
                setParent(currentHost)
            } else {
                setParent(currentHost, node.parent)
            }
            for (child in node.children) addChild(currentHost, child)
            text = node.text.ifEmpty { null }
            contentDescription = node.description.ifEmpty { null }
            if (node.parent == 0) paneTitle = node.windowTitle.ifEmpty { null }
            isEnabled = node.has(FLAG_ENABLED)
            isFocusable = node.has(FLAG_FOCUSABLE)
            isFocused = node.id == inputFocus
            isClickable = node.has(FLAG_CLICKABLE)
            isEditable = node.has(FLAG_EDITABLE)
            isCheckable = node.has(FLAG_CHECKABLE)
            isChecked = node.has(FLAG_CHECKED)
            isSelected = node.has(FLAG_SELECTED)
            isPassword = node.has(FLAG_PASSWORD)
            isAccessibilityFocused = node.id == accessibilityFocus
            if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
            if (isEnabled && isClickable) addAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (isEnabled && isEditable) addAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
            if (isEnabled && node.has(FLAG_SCROLL_FORWARD)) {
                addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (isEnabled && node.has(FLAG_SCROLL_BACKWARD)) {
                addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            if (isEnabled && isFocusable) addAction(AccessibilityNodeInfo.ACTION_FOCUS)
            addAction(
                if (node.id == accessibilityFocus) {
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                } else {
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                },
            )
            val windowBounds = displayBounds(node, currentHost)
            val parentBounds = Rect(windowBounds)
            if (node.parent != 0) {
                currentTree.nodes[node.parent]?.let { parent ->
                    val scaledParent = displayBounds(parent, currentHost)
                    parentBounds.offset(-scaledParent.left, -scaledParent.top)
                }
            }
            setBoundsInParent(parentBounds)
            val location = IntArray(2)
            currentHost.getLocationOnScreen(location)
            setBoundsInScreen(
                Rect(windowBounds).apply { offset(location[0], location[1]) },
            )
            isVisibleToUser =
                currentHost.isShown &&
                Rect.intersects(
                    Rect(0, 0, currentHost.width, currentHost.height),
                    windowBounds,
                )
        }
    }

    override fun findAccessibilityNodeInfosByText(
        searched: String?,
        virtualViewId: Int,
    ): List<AccessibilityNodeInfo> {
        if (searched.isNullOrBlank()) return emptyList()
        val match = searched.lowercase(Locale.ROOT)
        val result = ArrayList<AccessibilityNodeInfo>()
        for (node in tree.ordered) {
            if (
                node.text.lowercase(Locale.ROOT).contains(match) ||
                node.description.lowercase(Locale.ROOT).contains(match)
            ) {
                createAccessibilityNodeInfo(node.id)?.let(result::add)
            }
        }
        return result
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? {
        val id =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
                accessibilityFocus
            } else {
                inputFocus
            }
        return if (id == 0) null else createAccessibilityNodeInfo(id)
    }

    override fun performAction(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        val node = tree.nodes[virtualViewId] ?: return false
        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
            accessibilityFocus = virtualViewId
            sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
            return true
        }
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            if (accessibilityFocus != virtualViewId) return false
            accessibilityFocus = 0
            sendEvent(
                virtualViewId,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            )
            return true
        }
        if (!node.has(FLAG_ENABLED)) return false
        val name: String
        val text: String
        when {
            action == AccessibilityNodeInfo.ACTION_CLICK &&
                node.has(FLAG_CLICKABLE) -> {
                name = "click"
                text = ""
            }
            action == AccessibilityNodeInfo.ACTION_FOCUS &&
                node.has(FLAG_FOCUSABLE) -> {
                name = "focus"
                text = ""
            }
            action == AccessibilityNodeInfo.ACTION_SET_TEXT &&
                node.has(FLAG_EDITABLE) -> {
                name = "set-text"
                text =
                    arguments
                        ?.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        )?.toString()
                        .orEmpty()
                if (
                    text.length > MAX_TEXT_UTF16 ||
                    text.toByteArray(StandardCharsets.UTF_8).size > MAX_TEXT_BYTES
                ) {
                    return false
                }
            }
            action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD &&
                node.has(FLAG_SCROLL_FORWARD) -> {
                name = "scroll-forward"
                text = ""
            }
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD &&
                node.has(FLAG_SCROLL_BACKWARD) -> {
                name = "scroll-backward"
                text = ""
            }
            else -> return false
        }
        if (!submitAction(virtualViewId, name, text)) return false
        if (name == "focus") inputFocus = virtualViewId
        return true
    }

    private fun sendEvent(
        nodeId: Int,
        type: Int,
    ) {
        val currentHost = host ?: return
        val node = tree.nodes[nodeId]
        currentHost.post {
            val manager =
                currentHost.context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE,
                ) as? AccessibilityManager
            if (
                host !== currentHost ||
                manager?.isEnabled != true ||
                currentHost.parent == null
            ) {
                return@post
            }
            runCatching {
                val event =
                    AccessibilityEvent.obtain(type).apply {
                        packageName = currentHost.context.packageName
                        if (nodeId == 0) {
                            className = currentHost.javaClass.name
                            setSource(currentHost)
                            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                                contentChangeTypes =
                                    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                            }
                        } else {
                            className = androidClass(node?.role.orEmpty())
                            setSource(currentHost, nodeId)
                            if (node?.text?.isNotEmpty() == true) text.add(node.text)
                        }
                    }
                currentHost.parent.requestSendAccessibilityEvent(currentHost, event)
            }
        }
    }

    private fun displayBounds(
        node: Node,
        currentHost: View,
    ): Rect {
        val currentTree = tree
        val width = currentHost.width.coerceAtLeast(1)
        val height = currentHost.height.coerceAtLeast(1)
        val transform = viewportTransform
        val mapped =
            if (transform == null) {
                mapAccessibilityDisplayBoundsFallback(
                    node.bounds.left,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.bottom,
                    currentTree.viewportWidth,
                    currentTree.viewportHeight,
                    width,
                    height,
                )
            } else {
                mapAccessibilityDisplayBounds(
                    node.bounds.left,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.bottom,
                    currentTree.viewportWidth,
                    currentTree.viewportHeight,
                    transform,
                    width,
                    height,
                )
            }
        return Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }

    private fun parse(input: ByteBuffer): Tree {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        fun readInt(): Int {
            require(input.remaining() >= Int.SIZE_BYTES)
            return input.int
        }
        fun readUnsignedShort(): Int {
            require(input.remaining() >= Short.SIZE_BYTES)
            return input.short.toInt() and 0xffff
        }
        fun readString(
            length: Int,
            maximumBytes: Int,
            maximumUtf16: Int,
        ): String {
            require(length in 0..maximumBytes && input.remaining() >= length)
            val encoded = input.slice().apply { limit(length) }
            input.position(input.position() + length)
            val value =
                decoder
                    .reset()
                    .decode(encoded)
                    .toString()
            require(value.length <= maximumUtf16 && value.none { it == '\u0000' })
            return value
        }

        val magic = ByteArray(TREE_MAGIC.size)
        require(input.remaining() >= magic.size)
        input.get(magic)
        require(magic.contentEquals(TREE_MAGIC))
        require(readInt() == TREE_VERSION)
        val viewportWidth = readInt()
        val viewportHeight = readInt()
        val count = readInt()
        require(
            viewportWidth in 1..MAX_VIEWPORT &&
                viewportHeight in 1..MAX_VIEWPORT &&
                count in 0..MAX_NODES,
        )
        val nodes = SparseArray<Node>(count)
        val ordered = arrayOfNulls<Node>(count)
        val mutableChildren = SparseArray<MutableList<Int>>(count)
        repeat(count) { index ->
            val id = readInt()
            val parent = readInt()
            val x = readInt()
            val y = readInt()
            val width = readInt()
            val height = readInt()
            val flags = readInt()
            val roleLength = readUnsignedShort()
            val textLength = readUnsignedShort()
            val descriptionLength = readUnsignedShort()
            val windowTitleLength = readUnsignedShort()
            require(
                id in 1..MAX_NODE_ID &&
                    parent in 0..MAX_NODE_ID &&
                    x in -MAX_VIEWPORT..MAX_VIEWPORT &&
                    y in -MAX_VIEWPORT..MAX_VIEWPORT &&
                    width in 1..MAX_VIEWPORT &&
                    height in 1..MAX_VIEWPORT &&
                    flags and FLAG_MASK.inv() == 0 &&
                    nodes[id] == null,
            )
            val role = readString(roleLength, MAX_ROLE_BYTES, MAX_ROLE_BYTES)
            require(role in ROLES)
            val text = readString(textLength, MAX_TEXT_BYTES, MAX_TEXT_UTF16)
            val description =
                readString(descriptionLength, MAX_TEXT_BYTES, MAX_TEXT_UTF16)
            val windowTitle =
                readString(windowTitleLength, MAX_TEXT_BYTES, MAX_TEXT_UTF16)
            val node =
                Node(
                    id,
                    parent,
                    role,
                    text,
                    description,
                    windowTitle,
                    Rect(
                        x,
                        y,
                        Math.addExact(x, width),
                        Math.addExact(y, height),
                    ),
                    flags,
                )
            nodes.put(id, node)
            ordered[index] = node
            mutableChildren.put(id, ArrayList())
        }
        require(!input.hasRemaining())
        var roots = 0
        for (index in 0 until count) {
            val node = checkNotNull(ordered[index])
            if (node.parent == 0) {
                roots++
            } else {
                require(nodes[node.parent] != null && node.parent != node.id)
                checkNotNull(mutableChildren[node.parent]).add(node.id)
            }
            var current = node.parent
            repeat(count) {
                if (current == 0) return@repeat
                current = nodes[current]?.parent ?: throw IOException("Missing parent")
            }
            require(current == 0)
        }
        require(count == 0 || roots > 0)
        for (index in 0 until count) {
            val node = checkNotNull(ordered[index])
            node.children = checkNotNull(mutableChildren[node.id]).toIntArray()
        }
        @Suppress("UNCHECKED_CAST")
        return Tree(
            viewportWidth,
            viewportHeight,
            nodes,
            ordered as Array<Node>,
        )
    }

    private fun androidClass(role: String): String =
        when (role) {
            "button" -> "android.widget.Button"
            "checkbox" -> "android.widget.CheckBox"
            "radio" -> "android.widget.RadioButton"
            "edit", "text-field" -> "android.widget.EditText"
            "image" -> "android.widget.ImageView"
            "list", "menu" -> "android.widget.ListView"
            "menu-item" -> "android.widget.Button"
            "slider" -> "android.widget.SeekBar"
            "text", "label" -> "android.widget.TextView"
            else -> "android.view.View"
        }

    private companion object {
        private val TREE_MAGIC =
            byteArrayOf(
                'A'.code.toByte(),
                'R'.code.toByte(),
                'C'.code.toByte(),
                'H'.code.toByte(),
                'A'.code.toByte(),
                'T'.code.toByte(),
                'S'.code.toByte(),
                'P'.code.toByte(),
            )
        private const val TREE_VERSION = 1
        private const val TREE_HEADER_BYTES = 24
        private const val MAX_TREE_BYTES = 1024 * 1024
        private const val POST_TRANSITION_BOUNDS_FRAMES = 12
        private const val INITIAL_TREE_BUFFER_BYTES = 16 * 1024
        private const val MAX_NODES = 1024
        private const val MAX_NODE_ID = 1_000_000
        private const val MAX_VIEWPORT = 16_384
        private const val MAX_ROLE_BYTES = 64
        private const val MAX_TEXT_BYTES = 4_096
        private const val MAX_TEXT_UTF16 = 1_024
        private const val FLAG_ENABLED = 1 shl 0
        private const val FLAG_FOCUSABLE = 1 shl 1
        private const val FLAG_CLICKABLE = 1 shl 2
        private const val FLAG_EDITABLE = 1 shl 3
        private const val FLAG_CHECKABLE = 1 shl 4
        private const val FLAG_CHECKED = 1 shl 5
        private const val FLAG_SELECTED = 1 shl 6
        private const val FLAG_PASSWORD = 1 shl 7
        private const val FLAG_SCROLL_FORWARD = 1 shl 8
        private const val FLAG_SCROLL_BACKWARD = 1 shl 9
        private const val FLAG_MASK = (1 shl 10) - 1
        private val ROLES =
            setOf(
                "window",
                "view",
                "button",
                "checkbox",
                "radio",
                "edit",
                "text-field",
                "image",
                "list",
                "list-item",
                "menu",
                "menu-item",
                "slider",
                "text",
                "label",
            )

        private fun nextBufferSize(required: Int): Int {
            var size = INITIAL_TREE_BUFFER_BYTES
            while (size < required) size = Math.multiplyExact(size, 2)
            return size.coerceAtMost(MAX_TREE_BYTES)
        }

        private fun emptyTree() = Tree(1, 1, SparseArray(), emptyArray())

        private const val TAG = "ArchpheneAccessibility"
    }
}

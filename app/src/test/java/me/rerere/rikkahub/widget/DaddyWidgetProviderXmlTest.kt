package me.rerere.rikkahub.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class DaddyWidgetProviderXmlTest {
    @Test
    fun `widget providers expose requested launcher cells`() {
        assertWidgetCells("daddy_widget_2x2.xml", width = "2", height = "2")
        assertWidgetCells("daddy_widget_2x4.xml", width = "2", height = "4")
        assertWidgetCells("daddy_widget_4x4.xml", width = "4", height = "4")
    }

    @Test
    fun `widget providers use a safe non blank initial layout`() {
        assertInitialLayout("daddy_widget_2x2.xml", "@layout/widget_daddy_initial")
        assertInitialLayout("daddy_widget_2x4.xml", "@layout/widget_daddy_initial")
        assertInitialLayout("daddy_widget_4x4.xml", "@layout/widget_daddy_initial")
        assertPreviewLayout("daddy_widget_2x2.xml", "@layout/widget_daddy_initial")
        assertPreviewLayout("daddy_widget_2x4.xml", "@layout/widget_daddy_initial")
        assertPreviewLayout("daddy_widget_4x4.xml", "@layout/widget_daddy_initial")
    }

    @Test
    fun `status widget layout avoids unsupported raw view nodes`() {
        val file = File("src/main/res/layout/widget_daddy_status.xml")
        val nodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("View")

        assertEquals(0, nodes.length)
    }

    @Test
    fun `status widget layout uses black red archive card elements`() {
        val text = File("src/main/res/layout/widget_daddy_status.xml").readText()

        assert(text.contains("@+id/widget_red_axis"))
        assert(text.contains("@+id/widget_seq"))
        assert(text.contains("@+id/widget_sys_tag"))
        assert(text.contains("@+id/widget_archive_label"))
        assert(text.contains("#FFFF0000"))
        assert(text.contains("ARCHIVE"))
    }

    private fun assertWidgetCells(fileName: String, width: String, height: String) {
        val element = parseWidgetProvider(fileName)

        assertEquals(width, element.getAttributeNS(ANDROID_NS, "targetCellWidth"))
        assertEquals(height, element.getAttributeNS(ANDROID_NS, "targetCellHeight"))
    }

    private fun assertInitialLayout(fileName: String, layout: String) {
        val element = parseWidgetProvider(fileName)

        assertEquals(layout, element.getAttributeNS(ANDROID_NS, "initialLayout"))
    }

    private fun assertPreviewLayout(fileName: String, layout: String) {
        val element = parseWidgetProvider(fileName)

        assertEquals(layout, element.getAttributeNS(ANDROID_NS, "previewLayout"))
    }

    private fun parseWidgetProvider(fileName: String) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/$fileName"))
            .documentElement

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

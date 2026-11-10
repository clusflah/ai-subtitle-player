package org.xmlpull.v1

import java.io.InputStream
import java.io.Reader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

interface XmlPullParser {
    fun setFeature(name: String, state: Boolean)
    fun setInput(inReader: Reader)
    fun setInput(inputStream: InputStream, inputEncoding: String?)
    val eventType: Int
    fun next(): Int
    fun nextText(): String
    val name: String?
    val text: String?
    val namespace: String?
    fun getAttributeValue(namespace: String?, name: String): String?

    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces"
    }
}

fun XmlPullParser.getEventType(): Int = eventType

internal class StaxXmlPullParser : XmlPullParser {
    private var reader: XMLStreamReader? = null
    private var currentEvent: Int = XmlPullParser.START_DOCUMENT
    private var currentName: String? = null
    private var currentText: String? = null
    private var currentNamespace: String? = null

    private fun factory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        runCatching { setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true) }
        runCatching { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
        runCatching { setProperty("javax.xml.stream.isSupportingExternalEntities", false) }
    }

    override fun setFeature(name: String, state: Boolean) {}

    override fun setInput(inReader: Reader) {
        reader = factory().createXMLStreamReader(inReader)
        currentEvent = XmlPullParser.START_DOCUMENT
    }

    override fun setInput(inputStream: InputStream, inputEncoding: String?) {
        reader = if (inputEncoding != null) {
            factory().createXMLStreamReader(inputStream, inputEncoding)
        } else {
            factory().createXMLStreamReader(inputStream)
        }
        currentEvent = XmlPullParser.START_DOCUMENT
    }

    override val eventType: Int get() = currentEvent

    override fun next(): Int {
        val r = reader ?: return XmlPullParser.END_DOCUMENT
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    currentEvent = XmlPullParser.START_TAG
                    currentName = r.localName
                    currentNamespace = r.namespaceURI
                    currentText = null
                    return currentEvent
                }
                XMLStreamConstants.END_ELEMENT -> {
                    currentEvent = XmlPullParser.END_TAG
                    currentName = r.localName
                    currentNamespace = r.namespaceURI
                    currentText = null
                    return currentEvent
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    currentEvent = XmlPullParser.TEXT
                    currentText = r.text
                    return currentEvent
                }
                XMLStreamConstants.END_DOCUMENT -> {
                    currentEvent = XmlPullParser.END_DOCUMENT
                    return currentEvent
                }
            }
        }
        currentEvent = XmlPullParser.END_DOCUMENT
        return currentEvent
    }

    override fun nextText(): String {
        val r = reader ?: return ""
        return r.elementText ?: ""
    }

    override val name: String? get() = currentName
    override val text: String? get() = currentText
    override val namespace: String? get() = currentNamespace

    override fun getAttributeValue(namespace: String?, name: String): String? {
        val r = reader ?: return null
        return r.getAttributeValue(namespace, name)
    }
}

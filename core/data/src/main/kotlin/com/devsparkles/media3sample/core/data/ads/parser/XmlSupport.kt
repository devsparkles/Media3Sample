package com.devsparkles.media3sample.core.data.ads.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * RÔLE : utilitaires XML partagés par VmapParser et VastParser.
 *
 * Points d'attention (bonnes questions d'entretien) :
 *  - SÉCURITÉ : le XML vient d'un tiers (régie pub). On désactive les DOCTYPE / entités
 *    externes pour éviter les attaques XXE (XML External Entity).
 *    https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing
 *  - NAMESPACES : un VMAP utilise le préfixe "vmap:" (vmap:AdBreak). On parse en mode
 *    namespace-aware et on compare le `localName` ("AdBreak") pour être robuste au préfixe.
 *  - CDATA : les URLs sont presque toujours dans des <![CDATA[ ... ]]>. `textContent` gère ça.
 */
internal object XmlSupport {

    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Certaines implémentations (Android) ne supportent pas toutes les features :
            // on les tente une par une sans faire échouer le parsing.
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        // trim() : certains serveurs renvoient des espaces/BOM avant <?xml ...?> -> erreur sinon.
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    /** Enfants DIRECTS ayant ce nom local (sans préfixe de namespace). */
    fun Element.children(localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nameOf() == localName) result += node as Element
        }
        return result
    }

    fun Element.child(localName: String): Element? = children(localName).firstOrNull()

    /** Descend un chemin d'enfants directs : path("Creatives", "Creative"). */
    fun Element.path(vararg names: String): List<Element> =
        names.fold(listOf(this)) { current, name -> current.flatMap { it.children(name) } }

    fun Element.text(): String = textContent.orEmpty().trim()

    fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }

    private fun Node.nameOf(): String = localName ?: nodeName.substringAfter(':')
}

package eu.eidas.auth.commons.xml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import eu.eidas.auth.commons.EidasStringUtil;
import eu.eidas.util.Preconditions;

/**
 * Utility class used to create the document builder factory with a sufficient level of security. See
 * https://www.owasp.org/index.php/XML_Entity_(XXE)_Processing for more details
 */
public final class DocumentBuilderFactoryUtil {

    // See http://stackoverflow.com/questions/9828254/is-documentbuilderfactory-thread-safe-in-java-5
    // See also org.opensaml.xml.parse.ParserPool -- Code removed : private static DocumentBuilderFactory dbf = null

    /**
     * The Document Builder Factory.
     */
    private static final Queue<DocumentBuilderFactory> DOCUMENT_BUILDER_FACTORY_POOL =
            new ConcurrentLinkedQueue<DocumentBuilderFactory>();

    private static final Queue<DocumentBuilder> DOCUMENT_BUILDER_POOL = new ConcurrentLinkedQueue<DocumentBuilder>();

    private static final Queue<TransformerFactory> TRANSFORMER_FACTORY_POOL =
            new ConcurrentLinkedQueue<TransformerFactory>();

    private static final Queue<Transformer> TRANSFORMER_POOL = new ConcurrentLinkedQueue<Transformer>();

    /**
     * Configures a given DocumentBuilderFactory with security features turned on.
     *
     * @param documentBuilderFactory the instance to configure
     * @throws ParserConfigurationException if one of the features is not supported
     * @since 1.1
     */
    public static void configureSecurity(@Nonnull DocumentBuilderFactory documentBuilderFactory)
            throws ParserConfigurationException {
        Preconditions.checkNotNull(documentBuilderFactory, "documentBuilderFactory");

        documentBuilderFactory.setNamespaceAware(true);
        documentBuilderFactory.setIgnoringComments(true);

        for (final Map.Entry<String, Boolean> entry : getSecureDocumentBuilderFeatures().entrySet()) {
            documentBuilderFactory.setFeature(entry.getKey(), entry.getValue().booleanValue());
        }

        // and these as well, per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks" (see reference below)
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
    }

    /**
     * Build the default set of parser features to use. The default features set are: <ul> <li>{@link
     * javax.xml.XMLConstants#FEATURE_SECURE_PROCESSING} = true</li> <li>http://apache.org/xml/features/disallow-doctype-decl
     * = true</li> Reference : https://www.owasp.org/index.php/XML_External_Entity_%28XXE%29_Processing </ul>
     */
    @Nonnull
    public static Map<String, Boolean> getSecureDocumentBuilderFeatures() {
        Map<String, Boolean> features = new HashMap<String, Boolean>();
        features.put(XMLConstants.FEATURE_SECURE_PROCESSING, Boolean.TRUE);

        // Ignore the external DTD completely
        // Note: this is for Xerces only:
        features.put("http://apache.org/xml/features/nonvalidating/load-external-dtd", Boolean.FALSE);
        // This is the PRIMARY defense. If DTDs (doctypes) are disallowed, almost all XML entity attacks are prevented
        // Xerces 2 only - http://xerces.apache.org/xerces2-j/features.html#disallow-doctype-decl
        features.put("http://apache.org/xml/features/disallow-doctype-decl", Boolean.TRUE);

        // If you can't completely disable DTDs, then at least do the following:
        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-general-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-general-entities
        features.put("http://xml.org/sax/features/external-general-entities", Boolean.FALSE);

        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-parameter-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-parameter-entities
        features.put("http://xml.org/sax/features/external-parameter-entities", Boolean.FALSE);

        return features;
    }

    @Nonnull
    public static byte[] marshall(@Nonnull Node node, boolean omitXMLDeclaration) throws TransformerException {
        Preconditions.checkNotNull(node, "node");

        // See http://stackoverflow.com/questions/9828254/is-documentbuilderfactory-thread-safe-in-java-5
        Transformer transformer = TRANSFORMER_POOL.poll();
        try {
            if (null == transformer) {
                TransformerFactory transformerFactory = TRANSFORMER_FACTORY_POOL.poll();
                try {
                    if (null == transformerFactory) {
                        transformerFactory = TransformerFactory.newInstance();
                    }
                    transformer = transformerFactory.newTransformer();
                } finally {
                    TRANSFORMER_FACTORY_POOL.offer(transformerFactory);
                }
            }
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");

            final String omitXmlDeclarationString = omitXMLDeclaration ? "yes" : "false";
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, omitXmlDeclarationString);

            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            // Obtain a byte array representation of the marshalled SAML object
            DOMSource domSource = new DOMSource(node);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            StreamResult result = new StreamResult(baos);
            transformer.transform(domSource, result);
            return baos.toByteArray();
        } finally {
            TRANSFORMER_POOL.offer(transformer);
        }
    }

    @Nonnull
    public static Document newDocument() throws ParserConfigurationException {
        // See http://stackoverflow.com/questions/9828254/is-documentbuilderfactory-thread-safe-in-java-5
        DocumentBuilder documentBuilder = DOCUMENT_BUILDER_POOL.poll();
        try {
            documentBuilder = validateDocumentBuilder(documentBuilder);
            return documentBuilder.newDocument();
        } finally {
            DOCUMENT_BUILDER_POOL.offer(documentBuilder);
        }
    }

    /**
     * Returns a new DocumentBuilderFactory instance already set up with security features turned on.
     *
     * @return a new DocumentBuilderFactory instance already set up with security features turned on.
     * @throws ParserConfigurationException
     */
    @Nonnull
    public static DocumentBuilderFactory newSecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        configureSecurity(documentBuilderFactory);
        return documentBuilderFactory;
    }

    @Nonnull
    public static Document parse(@Nonnull InputStream xmlInputStream)
            throws IOException, SAXException, ParserConfigurationException {
        Preconditions.checkNotNull(xmlInputStream, "xmlInputStream");

        // See http://stackoverflow.com/questions/9828254/is-documentbuilderfactory-thread-safe-in-java-5
        DocumentBuilder documentBuilder = DOCUMENT_BUILDER_POOL.poll();
        try {
            documentBuilder = validateDocumentBuilder(documentBuilder);
            return documentBuilder.parse(xmlInputStream);
        } finally {
            DOCUMENT_BUILDER_POOL.offer(documentBuilder);
        }
    }

    @Nonnull
    public static Document parse(@Nonnull byte[] xmlBytes)
            throws IOException, SAXException, ParserConfigurationException {
        Preconditions.checkNotNull(xmlBytes, "xmlBytes");

        return parse(new ByteArrayInputStream(xmlBytes));
    }

    @Nonnull
    public static Document parse(@Nonnull String xmlString)
            throws IOException, SAXException, ParserConfigurationException {
        Preconditions.checkNotNull(xmlString, "xmlString");

        return parse(new ByteArrayInputStream(EidasStringUtil.getBytes(xmlString.trim())));
    }

    @Nonnull
    public static String toString(@Nonnull Node node) throws TransformerException {
        return EidasStringUtil.toString(marshall(node, true));
    }

    /**
     * If the instance retrieved from the Pool is null, create a new one using a pooled factory.
     *
     * @param documentBuilder a nullable instance
     * @return a non-null instance
     * @throws ParserConfigurationException if an instance could not be created
     */
    @Nonnull
    private static DocumentBuilder validateDocumentBuilder(@Nullable DocumentBuilder documentBuilder)
            throws ParserConfigurationException {
        if (null == documentBuilder) {
            DocumentBuilderFactory documentBuilderFactory = DOCUMENT_BUILDER_FACTORY_POOL.poll();
            try {
                if (null == documentBuilderFactory) {
                    documentBuilderFactory = newSecureDocumentBuilderFactory();
                }
                documentBuilder = documentBuilderFactory.newDocumentBuilder();
            } finally {
                DOCUMENT_BUILDER_FACTORY_POOL.offer(documentBuilderFactory);
            }
        }
        return documentBuilder;
    }

    private DocumentBuilderFactoryUtil() {
    }
}

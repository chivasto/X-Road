package ee.cyber.sdsb.proxyui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.wsdl.BindingOperation;
import javax.wsdl.Definition;
import javax.wsdl.Port;
import javax.wsdl.Service;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.UnknownExtensibilityElement;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap12.SOAP12Address;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLLocator;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.http.MimeTypes;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import ee.cyber.sdsb.common.CodedException;
import ee.cyber.sdsb.common.SystemProperties;
import ee.cyber.sdsb.common.message.Soap;
import ee.cyber.sdsb.common.message.SoapFault;
import ee.cyber.sdsb.common.message.SoapParser;
import ee.cyber.sdsb.common.message.SoapParserImpl;

import static ee.cyber.sdsb.common.ErrorCodes.X_INTERNAL_ERROR;
import static ee.cyber.sdsb.common.ErrorCodes.translateException;

@Slf4j
@SuppressWarnings("unchecked")
public class WSDLParser {

    private static final String SOAP_NAMESPACE =
            "http://schemas.xmlsoap.org/wsdl/soap/";

    private static final String SOAP12_NAMESPACE =
            "http://schemas.xmlsoap.org/wsdl/soap12/";

    private static final QName SOAP_ADDRESS =
            new QName(SOAP_NAMESPACE, "address");
    private static final QName SOAP_BINDING =
            new QName(SOAP_NAMESPACE, "binding");

    private static final QName SOAP12_ADDRESS =
            new QName(SOAP12_NAMESPACE, "address");
    private static final QName SOAP12_BINDING =
            new QName(SOAP12_NAMESPACE, "binding");

    private static final String TRANSPORT =
        "http://schemas.xmlsoap.org/soap/http";

    private static final String VERSION = "version";

    public static Collection<ServiceInfo> parseWSDL(String wsdlUrl)
            throws Exception {
        try {
            return internalParseWSDL(wsdlUrl);
        } catch (Exception e) {
            throw translateException(e);
        }
    }

    private static Collection<ServiceInfo> internalParseWSDL(String wsdlUrl)
            throws Exception {
        WSDLFactory wsdlFactory = WSDLFactory.newInstance(
                "com.ibm.wsdl.factory.WSDLFactoryImpl");

        WSDLReader wsdlReader = wsdlFactory.newWSDLReader();
        wsdlReader.setFeature("javax.wsdl.importDocuments", false);
        wsdlReader.setFeature("com.ibm.wsdl.parseXMLSchemas", false);

        Definition definition =
                wsdlReader.readWSDL(new TrustAllSslCertsWsdlLocator(wsdlUrl));

        Collection<Service> services = definition.getServices().values();

        Map<String, ServiceInfo> result = new HashMap<>();

        for (Service service : services) {
            for (Port port : (Collection<Port>) service.getPorts().values()) {
                if (!hasSoapOverHttpBinding(port)) {
                    continue;
                }

                String url = getUrl(port);
                for (BindingOperation operation
                        : (List<BindingOperation>)
                            port.getBinding().getBindingOperations()) {
                    String title = getChildValue("title",
                        operation.getOperation().getDocumentationElement());

                    String version = getVersion(operation);

                    result.put(operation.getName() + version, new ServiceInfo(
                        operation.getName(), title, url, version));
                }
            }
        }

        return result.values();
    }

    private static boolean hasSoapOverHttpBinding(Port port) throws Exception {
        for (ExtensibilityElement ext : (List<ExtensibilityElement>)
                 port.getBinding().getExtensibilityElements()) {

            if (ext.getElementType().equals(SOAP_BINDING)) {
                return TRANSPORT.equals(((SOAPBinding) ext).getTransportURI());
            }

            if (ext.getElementType().equals(SOAP12_BINDING)) {
                return TRANSPORT.equals(((SOAP12Binding) ext).getTransportURI());
            }
        }

        return false;
    }

    private static String getUrl(Port port) {
        for (ExtensibilityElement ext
                : (List<ExtensibilityElement>) port.getExtensibilityElements()) {
            if (ext.getElementType().equals(SOAP_ADDRESS)) {
                return ((SOAPAddress) ext).getLocationURI();
            }

            if (ext.getElementType().equals(SOAP12_ADDRESS)) {
                return ((SOAP12Address) ext).getLocationURI();
            }
        }

        return null;
    }

    private static String getVersion(BindingOperation operation) {
        for (ExtensibilityElement ext
                : (List<ExtensibilityElement>)
                    operation.getExtensibilityElements()) {
            if (ext.getElementType().getLocalPart().equals(VERSION)) {
                return getValue(
                        ((UnknownExtensibilityElement) ext).getElement());
            }
        }

        return null;
    }

    private static String getChildValue(String childName, Element element) {
        if (element == null) {
            return null;
        }

        NodeList nodeList = element.getChildNodes();

        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);

            if (childName.equals(node.getLocalName())) {
                return getValue(node);
            }
        }

        return null;
    }

    private static String getValue(Node node) {
        if (node.hasChildNodes()) {
            String value = node.getFirstChild().getNodeValue();

            if (value != null) {
                value = value.trim();
            }

            return value;
        }

        return null;
    }

    public static final class ServiceInfo {

        public final String name;
        public final String title;
        public final String url;
        public final String version;

        public ServiceInfo(String name, String title, String url,
                String version) {
            this.name = name;
            this.title = title;
            this.url = url;
            this.version = version;
        }
    }

    private static final class TrustAllSslCertsWsdlLocator
            implements WSDLLocator {

        private final String wsdlUrl;

        TrustAllSslCertsWsdlLocator(String wsdlUrl) {
            this.wsdlUrl = wsdlUrl;
        }

        @Override
        public InputSource getBaseInputSource() {
            try {
                URLConnection conn = new URL(wsdlUrl).openConnection();
                if (conn instanceof HttpsURLConnection) {
                    configureHttps((HttpsURLConnection) conn);
                }

                if (conn instanceof HttpURLConnection && wsdlUrl.startsWith(
                        SystemProperties.getServiceMediatorAddress())) {
                    checkForHttpError((HttpURLConnection) conn);
                }

                // cache the response
                byte[] response;
                try (InputStream in = conn.getInputStream()) {
                    response = IOUtils.toByteArray(in);
                }

                log.trace("Received WSDL response: {}", new String(response));
                checkForSoapFault(response);

                return new InputSource(new ByteArrayInputStream(response));
            } catch (CodedException e) {
                throw e;
            } catch (Exception e) {
                throw new CodedException(X_INTERNAL_ERROR, e);
            }
        }

        private void checkForHttpError(HttpURLConnection conn)
                throws Exception {
            if (conn.getResponseCode() == 500) {
                try (InputStream in = conn.getErrorStream()) {
                    throw CodedException.tr(X_INTERNAL_ERROR,
                        "listing_adapter_methods_failed",
                        IOUtils.toString(in, StandardCharsets.UTF_8.name()));
                }
            }
        }

        private void checkForSoapFault(byte[] response) {
            SoapParser parser = new SoapParserImpl();
            Soap soap = null;
            try {
                soap = parser.parse(MimeTypes.TEXT_XML,
                        StandardCharsets.UTF_8.name(),
                        new ByteArrayInputStream(response));
            } catch (Exception e) {
                // Ignore exceptions, since the response might have
                // been a valid WSDL, which SoapParser cannot parse.
                return;
            }

            if (soap instanceof SoapFault) {
                throw ((SoapFault) soap).toCodedException();
            }
        }

        @Override
        public InputSource getImportInputSource(String parentLocation,
                String importLocation) {
            return null;
        }

        @Override
        public String getBaseURI() {
            return wsdlUrl;
        }

        @Override
        public String getLatestImportURI() {
            return null;
        }

        @Override
        public void close() {
        }

        private void configureHttps(HttpsURLConnection conn)
                throws Exception {
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    @Override
                    public void checkClientTrusted(
                            X509Certificate[] certs, String authType) {
                    }
                    @Override
                    public void checkServerTrusted(
                            X509Certificate[] certs, String authType) {
                        // trust all
                    }
                }
            };

            SSLContext ctx = SSLContext.getInstance("SSL");
            ctx.init(null, trustAllCerts, new SecureRandom());

            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        }
    }
}

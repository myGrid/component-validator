package org.taverna.component.validator.support;

import java.util.HashMap;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.SchemaFactory;

import org.apache.xerces.dom.DOMInputImpl;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

public class JAXBSupport {
	private static SchemaFactory schemaFactory;
	static {
		schemaFactory = SchemaFactory
				.newInstance("http://www.w3.org/2001/XMLSchema");
		schemaFactory.setResourceResolver(new LSResourceResolver() {
			@SuppressWarnings("serial")
			private final Map<String, String> map = new HashMap<String, String>() {
				{
					put("http://www.w3.org/2001/XMLSchema.xsd",
							"/XMLSchema.xsd");
					put("http://www.w3.org/2001/xml.xsd", "/xml.xsd");
				}
			};

			@Override
			public LSInput resolveResource(String type, String namespaceURI,
					String publicId, String systemId, String baseURI) {
				if (map.containsKey(systemId))
					return new DOMInputImpl(publicId, JAXBSupport.class
							.getResource(map.get(systemId)).toExternalForm(),
							null);
				return null;
			}
		});
	}

	public static Marshaller makeValidatingMarshaller(JAXBContext context,
			String schemaLocation) throws JAXBException, SAXException {
		Marshaller marshaller = context.createMarshaller();
		marshaller.setSchema(schemaFactory.newSchema(JAXBSupport.class
				.getResource(schemaLocation)));
		return marshaller;
	}

	public static Unmarshaller makeValidatingUnmarshaller(JAXBContext context,
			String schemaLocation) throws JAXBException, SAXException {
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setSchema(schemaFactory.newSchema(JAXBSupport.class
				.getResource(schemaLocation)));
		return unmarshaller;
	}
}

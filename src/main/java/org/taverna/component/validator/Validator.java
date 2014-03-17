package org.taverna.component.validator;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.xpath.XPathExpressionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import uk.org.taverna.ns._2012.component.profile.Profile;

public class Validator extends XPathSupport {
	private final DocumentBuilderFactory docBuilderFactory;
	private final JAXBContext context;
	private final Logger log;

	public static void main(String... args) throws Exception {
		if (args.length < 2) {
			System.err.println("wrong # args: should be "
					+ "\"java -jar Validator.jar component-url profile-url\"");
			System.exit(1);
		}
		URL pwd = new File(".").getAbsoluteFile().toURI().toURL();
		URL componentUrl = new URL(pwd, args[0]);
		URL profileUrl = new URL(pwd, args[1]);

		new Validator().validate(componentUrl, profileUrl, System.out);
	}

	public Validator() throws JAXBException {
		super(LoggerFactory.getLogger(XPathSupport.class));
		log = LoggerFactory.getLogger(Validator.class);
		docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setNamespaceAware(true);
		context = JAXBContext.newInstance(Profile.class);
	}

	public void validate(URL componentUrl, URL profileUrl, PrintStream out)
			throws IOException, SAXException, ParserConfigurationException,
			JAXBException, XPathExpressionException {
		DocumentBuilder db = docBuilderFactory.newDocumentBuilder();
		db.setErrorHandler(new ErrorHandler() {
			@Override
			public void error(SAXParseException error) throws SAXException {
				log.error(
						"problem with parsing document: " + error.getMessage(),
						error);
			}

			@Override
			public void fatalError(SAXParseException fatal) throws SAXException {
				log.error(
						"major problem with parsing document: "
								+ fatal.getMessage(), fatal);
			}

			@Override
			public void warning(SAXParseException warning) throws SAXException {
				log.warn(warning.getMessage());
			}
		});
		Element component = db.parse(new InputSource(componentUrl.toString()))
				.getDocumentElement();
		realizeAttrs(component);
		Profile profile = context
				.createUnmarshaller()
				.unmarshal(
						new SAXSource(new InputSource(profileUrl.toString())),
						Profile.class).getValue();
		validate(component, profile, out);
	}

	public void validate(Element component, Profile profile, PrintStream out) {
		// FIXME Auto-generated method stub

	}

	private void realizeAttrs(Element base) throws XPathExpressionException {
		for (Node n : selectNodes(base, "//@*"))
			((Attr) n).getValue();
	}
}

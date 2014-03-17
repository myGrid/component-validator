package org.taverna.component.validator;

import static com.hp.hpl.jena.rdf.model.ModelFactory.createOntologyModel;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
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

import uk.org.taverna.ns._2012.component.profile.Activity;
import uk.org.taverna.ns._2012.component.profile.Component;
import uk.org.taverna.ns._2012.component.profile.ComponentAnnotation;
import uk.org.taverna.ns._2012.component.profile.Ontology;
import uk.org.taverna.ns._2012.component.profile.Port;
import uk.org.taverna.ns._2012.component.profile.PortAnnotation;
import uk.org.taverna.ns._2012.component.profile.Profile;
import uk.org.taverna.ns._2012.component.profile.SemanticAnnotation;

import com.hp.hpl.jena.ontology.OntModel;

import edu.umd.cs.findbugs.annotations.Nullable;

public class Validator extends XPathSupport {
	private final DocumentBuilderFactory docBuilderFactory;
	private final JAXBContext context;
	private final Logger log;
	private static final String TOP = "/t:workflow/t:dataflow[@role='top']";

	public static void main(String... args) throws Exception {
		if (args.length < 2) {
			System.err.println("wrong # args: should be "
					+ "\"java -jar Validator.jar component-url profile-url\"");
			System.exit(1);
		}
		URL pwd = new File(".").getAbsoluteFile().toURI().toURL();
		new Validator().validate(new URL(pwd, args[0]), new URL(pwd, args[1]),
				System.out);
	}

	public Validator() throws JAXBException {
		super(LoggerFactory.getLogger(XPathSupport.class));// FIXME! Namespaces!
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
		List<Profile> profiles = getProfiles(profileUrl.toString());
		validate(component, profiles, out);
	}

	public List<Profile> getProfiles(String root) throws JAXBException {
		Unmarshaller u = context.createUnmarshaller();
		List<Profile> result = new ArrayList<>();
		String where = root;
		while (true) {
			Profile p = u.unmarshal(new SAXSource(new InputSource(where)),
					Profile.class).getValue();
			result.add(p);
			if (p.getExtends() == null)
				break;
			// TODO qualify this URL (is it a URL?)
			where = p.getExtends().getProfileId();
		}
		// TODO load root profile?
		return result;
	}

	static class Assertion {
		public Assertion(boolean isSatisified, String message) {
			this.satisfied = isSatisified;
			this.text = message;
		}

		public final String text;
		public final boolean satisfied;
	}

	public boolean validate(Element component, List<Profile> profiles,
			PrintStream out) throws IOException, XPathExpressionException {
		List<Assertion> assertions = new ArrayList<>();
		Map<String, OntModel> ontocache = new HashMap<>();
		for (Profile p : profiles) {
			Map<String, OntModel> ontomap = new HashMap<>();
			for (Ontology o : p.getOntology()) {
				if (!ontocache.containsKey(o.getValue()))
					ontocache.put(o.getValue(), loadOntology(o.getValue()));
				ontomap.put(o.getId(), ontocache.get(o.getValue()));
			}
			assertions.addAll(validateComponent(component, p.getComponent(),
					ontomap));
		}
		return reportAssertionStatus(out, assertions);
	}

	public boolean reportAssertionStatus(PrintStream out,
			List<Assertion> assertions) {
		int sat = 0;
		for (Assertion a : assertions)
			if (a.satisfied)
				sat++;
		if (sat == assertions.size())
			out.println("SATISFIED (" + sat + "/" + assertions.size() + ")");
		else
			out.println("NOT SATISFIED (" + sat + "/" + assertions.size() + ")");
		out.println("");
		for (Assertion a : assertions)
			out.println(a.text);
		return sat == assertions.size();
	}

	private OntModel loadOntology(String ontologyURI) throws IOException {
		OntModel model = createOntologyModel();
		InputStream in = null;
		try {
			URL url = new URL(ontologyURI);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			/* CRITICAL: must be retrieved as correct content type */
			conn.addRequestProperty("Accept",
					"application/rdf+xml,application/xml;q=0.9");
			in = conn.getInputStream();
			// System.out.println(conn.getHeaderFields());
			if (conn.getContentEncoding() != null)
				model.read(new InputStreamReader(new BufferedInputStream(in),
						conn.getContentEncoding()), url.toString());
			else
				// Default the guessing to Jena...
				model.read(new BufferedInputStream(in), url.toString());
			return model;
		} finally {
			if (in != null)
				in.close();
		}
	}

	public List<Assertion> validateComponent(Element component,
			Component componentProfile, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Assertion> result = new ArrayList<>();
		for (ComponentAnnotation ca : componentProfile.getAnnotation())
			result.add(validateComponentBasicAnnotation(component, ca));
		for (SemanticAnnotation sa : componentProfile.getSemanticAnnotation())
			result.addAll(validateComponentSemanticAnnotation(component, sa,
					ontology));
		for (Port ip : componentProfile.getInputPort())
			result.addAll(validateInputPort(component, ip, ontology));
		for (Port op : componentProfile.getOutputPort())
			result.addAll(validateOutputPort(component, op, ontology));
		for (Activity ac : componentProfile.getActivity())
			result.add(validateActivity(component, ac, ontology));
		return result;
	}

	private Assertion validateActivity(Element component,
			Activity activityConstraint, Map<String, OntModel> ontology) {
		// TODO Auto-generated method stub
		return null;
	}

	private List<Assertion> validatePorts(String portType, Element portList,
			Port portConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Assertion> result = new ArrayList<>();
		if (portConstraint.getName() != null) {
			if (isMatched(portList, "./t:port/t:name = '%s'",
					portConstraint.getName())) {
				result.add(new Assertion(true, "Found " + portType
						+ " port called " + portConstraint.getName()));
				result.addAll(validateSinglePort(
						portType,
						get(portList, "./t:port[t:name='%s']",
								portConstraint.getName()), portConstraint,
						ontology));
			} else {
				if (portConstraint.getMinOccurs().intValue() > 0)
					result.add(new Assertion(false, "No " + portType
							+ " port called " + portConstraint.getName()));
				else
					result.add(new Assertion(true, "Port "
							+ portConstraint.getName()
							+ " is optional and absent"));
				result.addAll(validateAbsentPort(portType, portConstraint,
						ontology));
			}
			return result;
		}

		// TODO Auto-generated method stub
		return result;
	}

	private List<Assertion> validateAbsentPort(String portType,
			Port portConstraint, Map<String, OntModel> ontology) {
		List<Assertion> result = new ArrayList<>();
		// Mock up for absence
		result.add(new Assertion(true, "Ignoring depth constraints"));
		for (PortAnnotation ac : portConstraint.getAnnotation())
			result.add(new Assertion(true, "Ignoring "
					+ ac.getValue().value().value() + " requirement"));
		for (SemanticAnnotation sa : portConstraint.getSemanticAnnotation())
			result.addAll(validateOntologyAssertion(null, sa,
					ontology.get(sa.getOntology())));
		return result;
	}

	private List<Assertion> validateSinglePort(String portType, Element port,
			Port portConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		return null;// FIXME
	}

	private List<Assertion> validateOutputPort(Element component,
			Port portConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		return validatePorts("component output",
				get(component, TOP + "/t:outputPorts"), portConstraint,
				ontology);
	}

	private List<Assertion> validateInputPort(Element component,
			Port portConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		return validatePorts("component input",
				get(component, TOP + "/t:inputPorts"), portConstraint, ontology);
	}

	private List<Assertion> validateComponentSemanticAnnotation(
			Element component, SemanticAnnotation annotationConstraint,
			Map<String, OntModel> ontology) throws XPathExpressionException {
		OntModel model = ontology.get(annotationConstraint.getOntology());
		String term = "net.sf.taverna.t2.annotation.annotationbeans.SemanticAnnotation";
		String rdf = text(component, TOP
				+ "/t:annotations//annotationBean[@class='%s']/content", term);
		return validateOntologyAssertion(rdf, annotationConstraint, model);
	}

	private List<Assertion> validateOntologyAssertion(@Nullable String rdf,
			SemanticAnnotation annotationConstraint, OntModel model) {
		// FIXME Auto-generated method stub
		return null;
	}

	private Assertion validateComponentBasicAnnotation(Element component,
			ComponentAnnotation annotationConstraint)
			throws XPathExpressionException {
		String term;
		switch (annotationConstraint.getValue()) {
		case AUTHOR:
			term = "net.sf.taverna.t2.annotation.annotationbeans.Author";
			break;
		case DESCRIPTION:
			term = "net.sf.taverna.t2.annotation.annotationbeans.FreeTextDescription";
			break;
		case TITLE:
			term = "net.sf.taverna.t2.annotation.annotationbeans.DescriptiveTitle";
			break;
		default:
			throw new IllegalArgumentException();
		}
		if (isMatched(component, TOP
				+ "/t:annotations//annotationBean[@class='%s']", term))
			return new Assertion(true, String.format("Found %s for component",
					annotationConstraint.getValue()));
		else
			return new Assertion(false, String.format("No %s for component",
					annotationConstraint.getValue()));
	}

	private void realizeAttrs(Element base) throws XPathExpressionException {
		for (Node n : selectNodes(base, "//@*"))
			((Attr) n).getValue();
	}
}

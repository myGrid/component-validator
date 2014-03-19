package org.taverna.component.validator;

import static com.hp.hpl.jena.rdf.model.ModelFactory.createOntologyModel;
import static java.lang.Integer.parseInt;
import static java.util.Collections.unmodifiableMap;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.xpath.XPathExpressionException;

import org.slf4j.Logger;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import uk.org.taverna.ns._2012.component.profile.Activity;
import uk.org.taverna.ns._2012.component.profile.ActivityAnnotation;
import uk.org.taverna.ns._2012.component.profile.BasicAnnotations;
import uk.org.taverna.ns._2012.component.profile.Component;
import uk.org.taverna.ns._2012.component.profile.ComponentAnnotation;
import uk.org.taverna.ns._2012.component.profile.Ontology;
import uk.org.taverna.ns._2012.component.profile.Port;
import uk.org.taverna.ns._2012.component.profile.PortAnnotation;
import uk.org.taverna.ns._2012.component.profile.Profile;
import uk.org.taverna.ns._2012.component.profile.SemanticAnnotation;

import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;

import edu.umd.cs.findbugs.annotations.Nullable;

public class Validator extends XPathSupport {
	private static final String ANNOTATION_BEAN = "net.sf.taverna.t2.annotation.annotationbeans.SemanticAnnotation";
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
		super(getLogger(XPathSupport.class), "t",
				"http://taverna.sf.net/2008/xml/t2flow", "", "");
		log = getLogger(Validator.class);
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

	public static abstract class Assertion {
		Assertion(boolean isSatisified, boolean isWarning, String message) {
			this.satisfied = isSatisified;
			this.warning = isWarning;
			this.text = message;
		}

		public final String text;
		public final boolean satisfied;
		public final boolean warning;
	}

	public static class Fail extends Assertion {
		public Fail(String message, Object... args) {
			super(false, false, String.format(message, args));
		}
	}

	public static class Pass extends Assertion {
		public Pass(String message, Object... args) {
			this(false, message, args);
		}

		Pass(boolean warn, String message, Object... args) {
			super(true, warn, String.format(message, args));
		}
	}

	public static class Warn extends Pass {
		public Warn(String message, Object... args) {
			super(true, message, args);
		}
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

	private static final Map<String, String> TYPE_MAP;
	static {
		Map<String, String> map = new HashMap<>();
		map.put("Tool",
				"net.sf.taverna.t2.activities.externaltool.ExternalToolActivity");
		map.put("XPath", "net.sf.taverna.t2.activities.xpath.XPathActivity");
		map.put("Beanshell",
				"net.sf.taverna.t2.activities.beanshell.BeanshellActivity");
		TYPE_MAP = unmodifiableMap(map);
	}

	private Assertion validateActivity(Element component,
			Activity activityConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		String type = activityConstraint.getType();
		if (type != null) {
			String repltype = TYPE_MAP.get(type);
			if (repltype != null)
				type = repltype;
		}
		List<Element> activities;
		if (type == null)
			activities = select(component, ".//t:activities/t:activity");
		else
			activities = select(component,
					".//t:activities/t:activity[t:class='%s']", type);
		for (SemanticAnnotation sa : activityConstraint.getSemanticAnnotation()) {
			Iterator<Element> acit = activities.iterator();
			while (acit.hasNext()) {
				Element activity = acit.next();
				String rdf = text(activity,
						"./t:annotations//annotationBean[@class='%s']/content",
						ANNOTATION_BEAN);
				if (rdf == null || rdf.isEmpty()) {
					acit.remove();
					continue;
				}
				if (!satisfy(rdf, sa, ontology.get(sa.getOntology()))) {
					// TODO warn in this case?
					acit.remove();
					continue;
				}
			}
		}

		if (activities.size() < activityConstraint.getMinOccurs().intValue())
			return new Fail(
					"not enough %s activities in component to satisfy minimum (%s)",
					activityConstraint.getType(), activityConstraint
							.getMinOccurs());
		if (!activityConstraint.getMaxOccurs().equals("unbounded"))
			if (parseInt(activityConstraint.getMaxOccurs()) < activities.size())
				return new Fail(
						"too many %s activities to satisfy maximum (%s)",
						activityConstraint.getType(),
						activityConstraint.getMaxOccurs());
		if (!activityConstraint.getSemanticAnnotation().isEmpty()
				&& activities.isEmpty())
			return new Warn("no %s activity satisfies semantic constraints",
					activityConstraint.getType());

		for (Element activity : activities)
			for (ActivityAnnotation ba : activityConstraint.getAnnotation())
				if (!isMatched(activity,
						"./t:annotations//annotationBean[@class='%s']",
						getBasicAnnotationTerm(ba.getValue().value())))
					// TODO should this be a warning?
					return new Fail("no %s for %s activity in component",
							ba.getValue(), activityConstraint.getType());

		return new Pass(
				"confirmed semantic and cardinality constraints for %s activity(s)",
				activityConstraint.getType());
	}

	private List<Assertion> validatePorts(String portType, Element portList,
			Port portConstraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Assertion> result = new ArrayList<>();
		if (portConstraint.getName() != null) {
			if (isMatched(portList, "./t:port/t:name = '%s'",
					portConstraint.getName())) {
				result.add(new Pass("Found %s port called %s", portType,
						portConstraint.getName()));
				result.addAll(validateSinglePort(
						portType,
						get(portList, "./t:port[t:name='%s']",
								portConstraint.getName()), portConstraint,
						ontology));
			} else {
				if (portConstraint.getMinOccurs().intValue() > 0)
					result.add(new Fail("No %s port called %s", portType,
							portConstraint.getName()));
				else
					result.add(new Pass("Port %s is optional and absent",
							portConstraint.getName()));
				result.addAll(validateAbsentPort(portType, portConstraint,
						ontology));
			}
			return result;
		}

		List<SemanticAnnotation> selectionCriteria = new ArrayList<>();
		for (SemanticAnnotation sa : portConstraint.getSemanticAnnotation())
			if (sa.getMinOccurs().intValue() >= 1)
				selectionCriteria.add(sa);
		boolean mandate = !selectionCriteria.isEmpty();
		if (selectionCriteria.isEmpty())
			for (SemanticAnnotation sa : portConstraint.getSemanticAnnotation())
				if (sa.getMinOccurs().intValue() == 0
						&& !sa.getMaxOccurs().equals("0"))
					selectionCriteria.add(sa);
		List<Element> restrictedPortList = selectPorts(portList,
				selectionCriteria, ontology);
		if (restrictedPortList.isEmpty() && mandate)
			result.add(new Fail("No %s port matches semantic constraints",
					portType));
		// FIXME Auto-generated method stub
		return result;
	}

	private List<Element> selectPorts(Element portList,
			List<SemanticAnnotation> restrict, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Element> ports = select(portList, "./t:port");
		Iterator<Element> it = ports.iterator();
		mainloop: while (it.hasNext()) {
			Element port = it.next();
			Element content = getMaybe(port,
					".//annotationBean[@class='%s']/content", ANNOTATION_BEAN);
			if (content == null) {
				it.remove();
				continue;
			}
			OntModel rdf = parseRDF(content);

			for (SemanticAnnotation sa : restrict) {
				OntModel om = ontology.get(sa.getOntology());
				if (!satisfy(rdf, sa, om)) {
					it.remove();
					continue mainloop;
				}
			}
		}
		return ports;
	}

	// Mock up for absence
	private List<Assertion> validateAbsentPort(String portType,
			Port portConstraint, Map<String, OntModel> ontology) {
		List<Assertion> result = new ArrayList<>();
		result.add(new Warn("Ignoring depth constraints"));
		for (PortAnnotation ac : portConstraint.getAnnotation())
			result.add(new Warn("Ignoring %s requirement", ac.getValue()
					.value().value()));
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
		String rdf = text(component, TOP
				+ "/t:annotations//annotationBean[@class='%s']/content",
				ANNOTATION_BEAN);
		return validateOntologyAssertion(rdf, annotationConstraint, model);
	}

	private List<Assertion> validateOntologyAssertion(@Nullable String rdf,
			SemanticAnnotation annotationConstraint, OntModel model) {
		List<Assertion> result = new ArrayList<>();
		if (rdf == null) {
			// FIXME
		} else if (rdf.isEmpty() || !satisfy(rdf, annotationConstraint, model)) {
			// FIXME
		} else {
			// FIXME
		}
		return result;
	}

	private static String BASE = String.format("widget://%s/",
			UUID.randomUUID());
	protected static final String ENCODING = "TURTLE";

	private OntModel parseRDF(Element rdfContainer) {
		return parseRDF(rdfContainer.getTextContent());
	}

	private OntModel parseRDF(String rdf) {
		OntModel local = createOntologyModel();
		local.read(new StringReader(rdf), BASE, ENCODING);
		return local;
	}

	private boolean satisfy(String rdf,
			SemanticAnnotation annotationConstraint, OntModel model) {
		return satisfy(parseRDF(rdf), annotationConstraint, model);
	}

	private boolean satisfy(OntModel local,
			SemanticAnnotation annotationConstraint, OntModel model) {
		if (!annotationConstraint.getValue().isEmpty())
			return !local
					.listStatements(
							null,
							local.createProperty(annotationConstraint
									.getPredicate()),
							local.createResource(annotationConstraint
									.getValue())).toList().isEmpty();

		List<Statement> s = local.listStatements(null,
				local.createProperty(annotationConstraint.getPredicate()),
				(RDFNode) null).toList();
		if (s.isEmpty())
			return false;

		/*
		 * If there's no class constraint, we're done here.
		 */
		if (annotationConstraint.getClazz() == null)
			return true;

		RDFNode node = s.get(0).getObject();
		if (isInClass(node, annotationConstraint))
			return true;

		/*
		 * See if the model from the ontology knows anything about this
		 * individual.
		 */
		if (node.isResource()
				&& isInClass(model.getIndividual(node.asResource().getURI()),
						annotationConstraint))
			return true;

		// FIXME Not an individual! What to do here?
		return false;
	}

	private static boolean isInClass(RDFNode node,
			SemanticAnnotation annotationConstraint) {
		if (node == null)
			return false;
		if (node.isLiteral())
			return node.asLiteral().getDatatypeURI()
					.equals(annotationConstraint.getClazz());
		if (node.canAs(Individual.class))
			for (Resource clazz : node.as(Individual.class)
					.listOntClasses(false).toList())
				if (clazz.getURI().equals(annotationConstraint.getClazz()))
					return true;
		// What about other things?
		return false;
	}

	private String getBasicAnnotationTerm(BasicAnnotations ba) {
		switch (ba) {
		case AUTHOR:
			return "net.sf.taverna.t2.annotation.annotationbeans.Author";
		case DESCRIPTION:
			return "net.sf.taverna.t2.annotation.annotationbeans.FreeTextDescription";
		case EXAMPLE:
			return "net.sf.taverna.t2.annotation.annotationbeans.ExampleValue";
		case TITLE:
			return "net.sf.taverna.t2.annotation.annotationbeans.DescriptiveTitle";
		default:
			throw new IllegalStateException();
		}
	}

	private Assertion validateComponentBasicAnnotation(Element component,
			ComponentAnnotation annotationConstraint)
			throws XPathExpressionException {
		if (isMatched(component, TOP
				+ "/t:annotations//annotationBean[@class='%s']",
				getBasicAnnotationTerm(annotationConstraint.getValue().value())))
			return new Pass("Found %s for component",
					annotationConstraint.getValue());
		else
			return new Fail("No %s for component",
					annotationConstraint.getValue());
	}

	private void realizeAttrs(Element base) throws XPathExpressionException {
		for (Node n : selectNodes(base, "//@*"))
			((Attr) n).getValue();
	}
}

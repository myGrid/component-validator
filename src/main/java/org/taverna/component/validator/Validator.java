package org.taverna.component.validator;

import static com.hp.hpl.jena.rdf.model.ModelFactory.createOntologyModel;
import static java.lang.Class.forName;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableMap;
import static java.util.UUID.randomUUID;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.xpath.XPathExpressionException;

import org.slf4j.Logger;
import org.taverna.component.validator.Assertion.Fail;
import org.taverna.component.validator.Assertion.Pass;
import org.taverna.component.validator.Assertion.Warn;
import org.taverna.component.validator.AssertionReporter.JSONReporter;
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
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;

import edu.umd.cs.findbugs.annotations.Nullable;

public class Validator extends XPathSupport {
	private static final String TOP = "/t:workflow/t:dataflow[@role='top']";
	private static final String ANNOTATION_OF_CLASS = "./t:annotations//annotationBean[@class='%s']";
	private static final String SKOS_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
	private static final String ACTIVITIES_PKG = "net.sf.taverna.t2.activities.";
	private static final String ANNOTATION_PKG = "net.sf.taverna.t2.annotation.annotationbeans.";
	private static final String ANNOTATION_BEAN = ANNOTATION_PKG
			+ "SemanticAnnotation";
	private final DocumentBuilderFactory docBuilderFactory;
	private final JAXBContext context;
	private final Logger log;
	private static final RDFNode any = null;

	public static void main(String... args) throws Exception {
		if (args.length < 2) {
			System.err.println("wrong # args: should be "
					+ "\"java -jar Validator.jar component-url profile-url\"");
			System.exit(1);
		}
		URL pwd = new File(".").getAbsoluteFile().toURI().toURL();
		List<Assertion> assertions;
		try {
			// TODO separate loading model from actual validation
			assertions = new Validator().validate(new URL(pwd, args[0]),
					new URL(pwd, args[1]));
		} catch (FileNotFoundException | UnmarshalException e) {
			Throwable t = e;
			while (t.getCause() != null)
				t = t.getCause();
			if (t instanceof FileNotFoundException) {
				System.err.println(t.getMessage());
				System.exit(1);
			}
			throw e;
		}
		((AssertionReporter) forName(
				getProperty("validator.reporter.class",
						JSONReporter.class.getName())).newInstance())
				.reportAssertions(assertions);
	}

	public Validator() throws JAXBException {
		super(getLogger(XPathSupport.class), "t",
				"http://taverna.sf.net/2008/xml/t2flow", "", "");
		log = getLogger(Validator.class);
		docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setNamespaceAware(true);
		context = JAXBContext.newInstance(Profile.class);
	}

	public List<Assertion> validate(URL componentUrl, URL profileUrl)
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
		return validate(component, profiles);
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
			// FIXME how to map this to a URL!?
			where = p.getExtends().getProfileId();
			log.warn("cannot resolve " + where + " to a profile document");
			break;
		}
		// FIXME should be what we've last reached, not where we started
		if (!root.equals(BASE_PROFILE_URL))
			result.add(getBaseProfile());
		return result;
	}

	public List<Assertion> validate(Element component, List<Profile> profiles)
			throws IOException, XPathExpressionException {
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
		return assertions;
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
			if (conn.getContentEncoding() != null)
				model.read(new InputStreamReader(new BufferedInputStream(in),
						conn.getContentEncoding()), url.toString());
			else
				// Default the guessing to Jena...
				model.read(new BufferedInputStream(in), url.toString());
			return model;
		} catch (SocketException e) {
			log.error("failed to load ontology from " + ontologyURI
					+ " because of " + e.getMessage());
			throw e;
		} finally {
			if (in != null)
				in.close();
		}
	}

	protected List<Assertion> validateComponent(Element component,
			Component constraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Assertion> result = new ArrayList<>();
		for (ComponentAnnotation ca : constraint.getAnnotation())
			result.add(validateComponentBasicAnnotation(component, ca));
		for (SemanticAnnotation sa : constraint.getSemanticAnnotation())
			result.addAll(validateComponentSemanticAnnotation(component, sa,
					ontology));
		for (Port ip : constraint.getInputPort())
			result.addAll(validateInputPort(component, ip, ontology));
		for (Port op : constraint.getOutputPort())
			result.addAll(validateOutputPort(component, op, ontology));
		for (Activity ac : constraint.getActivity())
			result.add(validateActivity(component, ac, ontology));
		return result;
	}

	private static final Map<String, String> TYPE_MAP;
	static {
		Map<String, String> map = new HashMap<>();
		map.put("Tool", ACTIVITIES_PKG + "externaltool.ExternalToolActivity");
		map.put("XPath", ACTIVITIES_PKG + "xpath.XPathActivity");
		map.put("Beanshell", ACTIVITIES_PKG + "beanshell.BeanshellActivity");
		TYPE_MAP = unmodifiableMap(map);
	}

	private Assertion validateActivity(Element component, Activity constraint,
			Map<String, OntModel> ontology) throws XPathExpressionException {
		String type = constraint.getType();
		if (type != null) {
			String repltype = TYPE_MAP.get(type);
			if (repltype != null)
				type = repltype;
		}
		List<Element> activities;
		if (type == null)
			activities = select(component, ".//t:activities/t:activity");
		else
			activities = select(component, ".//t:activities/t:activity"
					+ "[t:class='%s']", type);
		for (SemanticAnnotation sa : constraint.getSemanticAnnotation()) {
			Iterator<Element> acit = activities.iterator();
			while (acit.hasNext()) {
				Element activity = acit.next();
				String rdf = text(activity, ANNOTATION_OF_CLASS + "/content",
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

		String desc = constraint.getType() == null ? "" : constraint.getType()
				+ " ";
		if (activities.size() < constraint.getMinOccurs().intValue())
			return new Fail("not enough %sactivities in component to "
					+ "satisfy minimum (%s)", desc, constraint.getMinOccurs());
		if (!constraint.getMaxOccurs().equals("unbounded"))
			if (parseInt(constraint.getMaxOccurs()) < activities.size())
				return new Fail("too many %sactivities to "
						+ "satisfy maximum (%s)", desc,
						constraint.getMaxOccurs());
		if (!constraint.getSemanticAnnotation().isEmpty()
				&& activities.isEmpty())
			return new Warn("no %sactivity satisfies semantic constraints",
					desc);

		for (Element activity : activities)
			for (ActivityAnnotation ba : constraint.getAnnotation())
				if (!isMatched(activity, ANNOTATION_OF_CLASS,
						getBasicAnnotationTerm(ba.getValue().value())))
					// TODO should this be a warning?
					return new Fail("no %s for %sactivity in component",
							ba.getValue(), desc);

		return new Pass("confirmed semantic and cardinality "
				+ "constraints for %sactivity(s)", desc);
	}

	private List<Assertion> validatePorts(String portType, Element portList,
			Port constraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Assertion> result = new ArrayList<>();
		List<Element> restrictedPortList;
		if (constraint.getName() != null) {
			if (!isMatched(portList, "./t:port[t:name = '%s']",
					constraint.getName())) {
				if (constraint.getMinOccurs().intValue() > 0)
					result.add(new Fail("no %s port called '%s'", portType,
							constraint.getName()));
				else
					result.add(new Pass("%s port '%s' is optional and absent",
							portType, constraint.getName()));
				result.addAll(validateAbsentPort(portType, constraint, ontology));
				return result;
			}
			result.add(new Pass("found %s port called '%s'", portType,
					constraint.getName()));
			Element port = get(portList, "./t:port[t:name='%s']",
					constraint.getName());
			Element rdfElement = getMaybe(port, ANNOTATION_OF_CLASS
					+ "/content", ANNOTATION_BEAN);
			if (rdfElement == null
					&& !constraint.getSemanticAnnotation().isEmpty())
				result.add(new Warn("no semantic annotation present for "
						+ "%s port '%s'", portType, constraint.getName()));
			else {
				OntModel rdf = parseRDF(rdfElement);
				for (SemanticAnnotation sa : constraint.getSemanticAnnotation()) {
					OntModel om = ontology.get(sa.getOntology());
					String predName = getName(
							om.getProperty(sa.getPredicate()), sa.getValue());
					if (satisfy(rdf, sa, om))
						result.add(new Pass("satisfied semantic annotation "
								+ "for property '%s' on %s port '%s'",
								predName, portType, constraint.getName()));
					else
						result.add(new Fail("failed semantic annotation "
								+ "for property '%s' on %s port '%s'",
								predName, portType, constraint.getName()));
				}
			}
			restrictedPortList = asList(port);
		} else {
			List<SemanticAnnotation> selectionCriteria = new ArrayList<>();
			for (SemanticAnnotation sa : constraint.getSemanticAnnotation())
				if (sa.getMinOccurs().intValue() >= 1)
					selectionCriteria.add(sa);
			boolean mandate = !selectionCriteria.isEmpty();
			if (selectionCriteria.isEmpty())
				for (SemanticAnnotation sa : constraint.getSemanticAnnotation())
					if (sa.getMinOccurs().intValue() == 0
							&& !sa.getMaxOccurs().equals("0"))
						selectionCriteria.add(sa);
			restrictedPortList = selectPorts(portList, selectionCriteria,
					ontology);
			if (restrictedPortList.isEmpty() && mandate)
				result.add(new Fail("no %s port matches semantic constraints",
						portType));
		}

		Map<Element, String> name = new HashMap<>();
		for (Element port : restrictedPortList)
			name.put(port, text(port, "./t:name"));

		for (PortAnnotation pa : constraint.getAnnotation())
			for (Element port : restrictedPortList)
				if (!isMatched(port, ANNOTATION_OF_CLASS,
						getBasicAnnotationTerm(pa.getValue().value())))
					result.add(new Fail("%s port '%s' lacks %s annotation",
							portType, name.get(port), pa.getValue().value()));
				else
					result.add(new Pass("%s port '%s' has %s annotation",
							portType, name.get(port), pa.getValue().value()));

		for (Element port : restrictedPortList) {
			if (text(port, "./t:depth").isEmpty()) {
				result.add(new Warn("no depth information for port '%s'",
						portType, name.get(port)));
				continue;
			}
			int depth = parseInt(text(port, "./t:depth"));
			if (depth < constraint.getMinDepth().intValue())
				result.add(new Fail("%s port '%s' is too shallow: "
						+ "%d instead of %s", portType, name.get(port), depth,
						constraint.getMinDepth()));
			else if (!constraint.getMaxDepth().equals("unbounded")
					&& depth > parseInt(constraint.getMaxDepth()))
				result.add(new Fail("%s port '%s' is too deep: "
						+ "%d instead of %s", portType, name.get(port), depth,
						constraint.getMaxDepth()));
			else
				result.add(new Pass("%s port '%s' depth is in permitted range",
						portType, name.get(port)));
		}
		return result;
	}

	private List<Element> selectPorts(Element portList,
			List<SemanticAnnotation> restrict, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		List<Element> ports = select(portList, "./t:port");
		Iterator<Element> it = ports.iterator();
		mainloop: while (it.hasNext()) {
			Element port = it.next();
			Element content = getMaybe(port, ANNOTATION_OF_CLASS + "/content",
					ANNOTATION_BEAN);
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

	private String getName(Resource node, String instance) {
		Statement s = node.getProperty(node.getModel().createProperty(
				SKOS_LABEL));
		String name = (s == null ? node.getLocalName() : s.getObject()
				.asLiteral().getString());
		if (instance != null && !instance.isEmpty()) {
			Model model = node.getModel();
			assert model != null;
			name += "(" + getName(model.createResource(instance), null) + ")";
		}
		return name;
	}

	// Mock up for absence
	private List<Assertion> validateAbsentPort(String portType,
			Port constraint, Map<String, OntModel> ontology) {
		List<Assertion> result = new ArrayList<>();
		result.add(new Warn("ignoring depth constraints"));
		for (PortAnnotation ac : constraint.getAnnotation())
			result.add(new Warn("ignoring %s requirement", ac.getValue()
					.value().value()));
		for (SemanticAnnotation sa : constraint.getSemanticAnnotation())
			result.addAll(validateOntologyAssertion(null, sa,
					ontology.get(sa.getOntology())));
		return result;
	}

	private List<Assertion> validateOutputPort(Element component,
			Port constraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		return validatePorts("component output",
				get(component, TOP + "/t:outputPorts"), constraint, ontology);
	}

	private List<Assertion> validateInputPort(Element component,
			Port constraint, Map<String, OntModel> ontology)
			throws XPathExpressionException {
		return validatePorts("component input",
				get(component, TOP + "/t:inputPorts"), constraint, ontology);
	}

	private List<Assertion> validateComponentSemanticAnnotation(
			Element component, SemanticAnnotation constraint,
			Map<String, OntModel> ontology) throws XPathExpressionException {
		OntModel model = ontology.get(constraint.getOntology());
		String rdf = text(component, TOP + "/" + ANNOTATION_OF_CLASS
				+ "/content", ANNOTATION_BEAN);
		return validateOntologyAssertion(rdf, constraint, model);
	}

	private List<Assertion> validateOntologyAssertion(
			@Nullable String rdfString, SemanticAnnotation constraint,
			OntModel model) {
		List<Assertion> result = new ArrayList<>();
		Property prop = model.createProperty(constraint.getPredicate());
		String propName = getName(prop, constraint.getValue());
		if (rdfString == null) {
			result.add(new Warn("no component-level semantic annotations; "
					+ "cannot check for '%s'", propName));
			return result;
		} else if (rdfString.isEmpty()) {
			result.add(new Fail("failed to satisfy '%s' annotation at "
					+ "component level", propName));
			return result;
		}

		OntModel rdf = parseRDF(rdfString);
		if (!satisfy(rdf, constraint, model)) {
			result.add(new Fail("failed to satisfy '%s' annotation at "
					+ "component level", propName));
		} else {
			result.add(new Pass("found '%s' annotation at component level",
					propName));
			RDFNode object = any;
			if (!constraint.getValue().isEmpty())
				object = model.createResource(constraint.getValue());
			int numsat = rdf.listStatements(null, prop, object).toList().size();
			if (numsat < constraint.getMinOccurs().intValue())
				result.add(new Fail("too few '%s' annotations at component "
						+ "level: %d instead of %s", propName, numsat,
						constraint.getMinOccurs()));
			else if (!constraint.getMaxOccurs().equals("unbounded")
					&& numsat > parseInt(constraint.getMaxOccurs()))
				result.add(new Fail("too many '%s' annotations at component "
						+ "level: %d instead of %s", propName, numsat,
						constraint.getMaxOccurs()));
			else
				result.add(new Pass("%d '%s' annotations at component level: "
						+ "in range %s to %s", numsat, propName, constraint
						.getMinOccurs(), constraint.getMaxOccurs()));
		}
		return result;
	}

	private static String BASE = format("widget://%s/", randomUUID());
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

	private boolean satisfy(OntModel local, SemanticAnnotation constraint,
			OntModel model) {
		if (!constraint.getValue().isEmpty())
			return !local
					.listStatements(null,
							local.createProperty(constraint.getPredicate()),
							local.createResource(constraint.getValue()))
					.toList().isEmpty();

		List<Statement> s = local.listStatements(null,
				local.createProperty(constraint.getPredicate()), any).toList();
		if (s.isEmpty())
			return false;

		/*
		 * If there's no class constraint, we're done here.
		 */
		if (constraint.getClazz() == null)
			return true;

		RDFNode node = s.get(0).getObject();
		if (isInClass(node, constraint))
			return true;

		/*
		 * See if the model from the ontology knows anything about this
		 * individual.
		 */
		if (node.isResource()
				&& isInClass(model.getIndividual(node.asResource().getURI()),
						constraint))
			return true;

		log.warn("object " + node + " is not an individual");
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
			return ANNOTATION_PKG + "Author";
		case DESCRIPTION:
			return ANNOTATION_PKG + "FreeTextDescription";
		case EXAMPLE:
			return ANNOTATION_PKG + "ExampleValue";
		case TITLE:
			return ANNOTATION_PKG + "DescriptiveTitle";
		default:
			throw new IllegalStateException();
		}
	}

	private Assertion validateComponentBasicAnnotation(Element component,
			ComponentAnnotation constraint) throws XPathExpressionException {
		if (isMatched(component, TOP + "/" + ANNOTATION_OF_CLASS,
				getBasicAnnotationTerm(constraint.getValue().value())))
			return new Pass("found %s for component", constraint.getValue());
		else
			return new Fail("no %s for component", constraint.getValue());
	}

	private void realizeAttrs(Element base) throws XPathExpressionException {
		for (Node n : selectNodes(base, "//@*"))
			((Attr) n).getValue();
	}

	private static final String BASE_PROFILE_URL = "http://build.mygrid.org.uk/taverna/BaseProfile.xml";
	private Profile cachedBaseProfile;

	private Profile getBaseProfile() throws JAXBException {
		try {
			if (cachedBaseProfile == null)
				cachedBaseProfile = (Profile) context.createUnmarshaller()
						.unmarshal(new URL(BASE_PROFILE_URL));
			return cachedBaseProfile;
		} catch (MalformedURLException e) {
			log.error("unexpected problem with creating URL"
					+ " for base profile", e);
			return null;
		}
	}
}

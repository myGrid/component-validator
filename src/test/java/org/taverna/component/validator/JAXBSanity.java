package org.taverna.component.validator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.taverna.component.validator.support.JAXBSupport.makeValidatingMarshaller;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.JAXBIntrospector;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;

import org.junit.Before;
import org.junit.Test;
import org.taverna.component.validator.support.JAXBSupport;
import org.xml.sax.SAXException;

import uk.org.taverna.ns._2012.component.profile.Component;
import uk.org.taverna.ns._2012.component.profile.Profile;

public class JAXBSanity {
	static final String NS = "http://ns.taverna.org.uk/2012/component/profile";

	JAXBContext context;

	@Before
	public void setup() throws JAXBException {
		context = JAXBContext.newInstance(Profile.class);
	}

	@Test
	public void testRootNs() {
		JAXBIntrospector intro = context.createJAXBIntrospector();
		assertEquals(new QName(NS, "profile"),
				intro.getElementName(new Profile()));
	}

	@Test
	public void testMarshal() throws JAXBException, SAXException {
		Marshaller m = makeValidatingMarshaller(context,
				"/ComponentProfile.xsd");
		m.setProperty("jaxb.fragment", true);
		StringWriter sw = new StringWriter();
		Profile p = new Profile();
		p.setId("foo");
		p.setName("bar");
		p.setDescription("foo bar");
		p.setComponent(new Component());
		m.marshal(p, sw);
		assertEquals("<profile xmlns=\"" + NS + "\"><id>foo</id>"
				+ "<name>bar</name><description>foo bar</description>"
				+ "<component/></profile>", sw.toString());
	}

	@Test
	public void testUnmarshal() throws JAXBException, SAXException {
		Unmarshaller u = JAXBSupport.makeValidatingUnmarshaller(context, "/ComponentProfile.xsd");
		StringReader sr = new StringReader("<profile xmlns=\"" + NS + "\"><id>foo</id>"
				+ "<name>bar</name><description>foo bar</description>"
				+ "<component/></profile>");
		Object o = u.unmarshal(sr);

		// Many bits to check here!
		assertEquals(Profile.class, o.getClass());
		Profile p = (Profile) o;
		assertEquals("foo", p.getId());
		assertEquals("bar", p.getName());
		assertEquals("foo bar", p.getDescription());
		assertNull(p.getExtends());
		assertEquals(0, p.getOntology().size());
		assertNotNull(p.getComponent());
		assertEquals(0,p.getComponent().getActivity().size());
		assertEquals(0,p.getComponent().getAnnotation().size());
		assertNull(p.getComponent().getExceptionHandling());
		assertEquals(0,p.getComponent().getInputPort().size());
		assertEquals(0,p.getComponent().getOutputPort().size());
		assertEquals(0,p.getComponent().getSemanticAnnotation().size());
	}
}

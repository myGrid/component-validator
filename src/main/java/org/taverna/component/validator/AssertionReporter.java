package org.taverna.component.validator;

import static java.lang.System.out;

import java.util.List;

public class AssertionReporter {
	public boolean reportAssertions(List<Assertion> assertions) {
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
}

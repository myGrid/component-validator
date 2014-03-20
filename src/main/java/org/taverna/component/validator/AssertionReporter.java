package org.taverna.component.validator;

import static java.lang.System.out;

import java.util.List;

public abstract class AssertionReporter {
	public abstract boolean reportAssertions(List<Assertion> assertions);

	public static class StdoutReporter extends AssertionReporter {
		@Override
		public boolean reportAssertions(List<Assertion> assertions) {
			int sat = 0;
			for (Assertion a : assertions)
				if (a.satisfied)
					sat++;
			if (sat == assertions.size())
				out.println("SATISFIED (" + sat + "/" + assertions.size() + ")");
			else
				out.println("NOT SATISFIED (" + sat + "/" + assertions.size()
						+ ")");
			out.println("");
			for (Assertion a : assertions)
				if (!a.satisfied)
					out.println("[N] " + a.text);
			for (Assertion a : assertions)
				if (a.satisfied && a.warning)
					out.println("[w] " + a.text);
			for (Assertion a : assertions)
				if (a.satisfied && !a.warning)
					out.println("[Y] " + a.text);
			return sat == assertions.size();
		}
	}
}
package org.taverna.component.validator;

import static java.lang.System.out;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public abstract class AssertionReporter {
	public boolean reportAssertions(List<Assertion> assertions) {
		int sat = 0;
		for (Assertion a : assertions)
			if (a.satisfied)
				sat++;
		return sat == assertions.size();
	}

	public static class StdoutReporter extends AssertionReporter {
		@Override
		public boolean reportAssertions(List<Assertion> assertions) {
			boolean satisfied = super.reportAssertions(assertions);
			int sat = 0;
			for (Assertion a : assertions)
				if (a.satisfied)
					sat++;
			if (satisfied)
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
			return satisfied;
		}
	}

	public static class JSONReporter extends AssertionReporter {
		@Override
		public boolean reportAssertions(List<Assertion> assertions) {
			boolean sat = super.reportAssertions(assertions);
			JSONArray ary = new JSONArray();
			for (Assertion a : assertions)
				ary.put(new JSONObject().put(
						"type",
						a.satisfied ? a.warning ? "warning" : "satisifed"
								: "failed").put("message", a.text));
			out.println(new JSONObject().put("allSatisfied", sat).put("assertions", ary));
			return sat;
		}
	}
}
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
			int f = 0, w = 0, s = 0;
			for (Assertion a : assertions)
				if (!a.satisfied) {
					ary.put(new JSONObject().put("type", "failed").put(
							"message", a.text));
					f++;
				}
			for (Assertion a : assertions)
				if (a.satisfied && a.warning) {
					ary.put(new JSONObject().put("type", "warning").put(
							"message", a.text));
					w++;
				}
			for (Assertion a : assertions)
				if (a.satisfied && !a.warning) {
					ary.put(new JSONObject().put("type", "satisifed").put(
							"message", a.text));
					s++;
				}
			out.println(new JSONObject().put("allSatisfied", sat)
					.put("assertions", ary).put("numSatisfied", s)
					.put("numWarning", w).put("numFailed", f)
					.put("numTotal", assertions.size()));
			return sat;
		}
	}
}
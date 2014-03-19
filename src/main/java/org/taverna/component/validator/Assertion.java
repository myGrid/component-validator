package org.taverna.component.validator;

public abstract class Assertion {
	Assertion(boolean isSatisified, boolean isWarning, String message) {
		this.satisfied = isSatisified;
		this.warning = isWarning;
		this.text = message;
	}

	public final String text;
	public final boolean satisfied;
	public final boolean warning;

	public static class Fail extends Assertion {
		public Fail(String message, Object... args) {
			super(false, false, String.format("[N] " + message, args));
		}
	}

	public static class Pass extends Assertion {
		public Pass(String message, Object... args) {
			this(false, "[Y] " + message, args);
		}

		Pass(boolean warn, String message, Object... args) {
			super(true, warn, String.format(message, args));
		}
	}

	public static class Warn extends Pass {
		public Warn(String message, Object... args) {
			super(true, "[W] " + message, args);
		}
	}
}
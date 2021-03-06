package de.tum.in.test.api;

import static de.tum.in.test.testutilities.CustomConditions.*;
import static org.junit.platform.testkit.engine.EventConditions.*;

import org.junit.platform.testkit.engine.Events;

import de.tum.in.test.testutilities.TestTest;
import de.tum.in.test.testutilities.UserBased;
import de.tum.in.test.testutilities.UserTestResults;
import de.tum.in.testuser.PathAccessUser;

@UserBased(PathAccessUser.class)
class PathAccessTest {

	@UserTestResults
	private static Events tests;

	private final String accessPathAllowed = "accessPathAllowed";
	private final String accessPathNormal = "accessPathNormal";
	private final String accessPathRelativeGlobA = "accessPathRelativeGlobA";
	private final String accessPathRelativeGlobB = "accessPathRelativeGlobB";
	private final String accessPathTest = "accessPathTest";
	private final String weAccessPath = "weAccessPath";

	@TestTest
	void test_accessPathAllowed() {
		tests.assertThatEvents().haveExactly(1, event(test(accessPathAllowed), finishedSuccessfullyRep()));
	}

	@TestTest
	void test_accessPathNormal() {
		tests.assertThatEvents().haveExactly(1, testFailedWith(accessPathNormal, SecurityException.class));
	}

	@TestTest
	void test_accessPathRelativeGlobA() {
		tests.assertThatEvents().haveExactly(1, event(test(accessPathRelativeGlobA), finishedSuccessfullyRep()));
	}

	@TestTest
	void test_accessPathRelativeGlobB() {
		tests.assertThatEvents().haveExactly(1, event(test(accessPathRelativeGlobB), finishedSuccessfullyRep()));
	}

	@TestTest
	void test_accessPathTest() {
		tests.assertThatEvents().haveExactly(1, testFailedWith(accessPathTest, SecurityException.class));
	}

	@TestTest
	void test_weAccessPath() {
		tests.assertThatEvents().haveExactly(1, event(test(weAccessPath), finishedSuccessfullyRep()));
	}
}

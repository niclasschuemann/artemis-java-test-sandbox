package de.tum.in.test.api.security;

import static de.tum.in.test.api.localization.Messages.*;

import java.awt.AWTPermission;
import java.io.FilePermission;
import java.io.SerializablePermission;
import java.lang.StackWalker.StackFrame;
import java.lang.Thread.State;
import java.lang.management.ManagementPermission;
import java.net.InetAddress;
import java.net.NetPermission;
import java.net.SocketPermission;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.security.SecurityPermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.net.ssl.SSLPermission;
import javax.security.auth.AuthPermission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.in.test.api.PathActionLevel;
import de.tum.in.test.api.localization.Messages;

/**
 * Prevents System.exit, reflection to a certain degree, networking use,
 * execution commands and long running threads.
 *
 * @author Christian Femers
 */
public final class ArtemisSecurityManager extends SecurityManager {

	private static final SecurityManager ORIGINAL = System.getSecurityManager();
	private static final ArtemisSecurityManager INSTANCE = new ArtemisSecurityManager();
	private static final Logger LOG = LoggerFactory.getLogger(ArtemisSecurityManager.class);
	private static final MessageDigest SHA256;
	static {
		try {
			SHA256 = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
		} catch (NoSuchAlgorithmException e) {
			throw new ExceptionInInitializerError(e);
		}
		// Allow to load resources
		Messages.init();
		/*
		 * Check for main Thread
		 */
		if (!Objects.equals("main", SecurityConstants.MAIN_THREAD.getName())) { //$NON-NLS-1$
			LOG.error("Expected ArtemisSecurityManager to be initialized in the main thread but was {}. Exiting...", //$NON-NLS-1$
					SecurityConstants.MAIN_THREAD);
			System.exit(1);
		}
	}

	private static final BiConsumer<String, Object> ON_MODIFICATION = (method, object) -> LOG
			.warn("addSuppressed, {} called with {}", method, object == null ? "null" : object.getClass()); //$NON-NLS-1$ //$NON-NLS-2$

	private final ThreadGroup testThreadGroup = new ThreadGroup("Test-Threadgroup"); //$NON-NLS-1$
	private final ThreadLocal<AtomicInteger> recursionBreak = ThreadLocal.withInitial(AtomicInteger::new);
	private final StackWalker stackWalker = StackWalker.getInstance();

	private ArtemisSecurityConfiguration configuration;
	private String accessToken;
	private Set<Thread> whitelistedThreads = new HashSet<>();
	private volatile boolean isPartlyDisabled;
	private volatile boolean blockThreadCreation;
	private volatile boolean lastUninstallFailed;

	private ArtemisSecurityManager() {
		if (INSTANCE != null)
			throw new IllegalStateException(localized("security.already_created")); //$NON-NLS-1$
	}

	private synchronized String generateAccessToken() {
		String tokenString = UUID.randomUUID().toString();
		accessToken = hash(tokenString);
		return tokenString;
	}

	private synchronized void checkAccess(String accessToken) {
		if (!this.accessToken.equals(hash(accessToken)))
			throw new SecurityException(localized("security.access_token_invalid")); //$NON-NLS-1$
	}

	private boolean enterPublicInterface() {
		return recursionBreak.get().getAndIncrement() > 2;
	}

	private boolean exitPublicInterface() {
		return recursionBreak.get().getAndDecrement() > 2;
	}

	@Override
	public void checkExit(int status) {
		try {
			if (enterPublicInterface())
				return;
			if (Thread.currentThread() == SecurityConstants.MAIN_THREAD && getNonWhitelistedStackFrames().isEmpty()) {
				// always allow maven to exit
				return;
			}
			throw new SecurityException(localized("security.error_system_exit")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkAccept(String host, int port) {
		try {
			if (enterPublicInterface())
				return;
			if (isConnectionAllowed(host, port))
				return;
			throw new SecurityException(formatLocalized("security.error_network_accept", host, port)); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkConnect(String host, int port) {
		try {
			if (enterPublicInterface())
				return;
			if (isConnectionAllowed(host, port))
				return;
			throw new SecurityException(formatLocalized("security.error_network_connect", host, port)); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkConnect(String host, int port, Object context) {
		try {
			if (enterPublicInterface())
				return;
			if (isConnectionAllowed(host, port))
				return;
			throw new SecurityException(formatLocalized("security.error_network_connect_with_context", host, port)); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkListen(int port) {
		try {
			if (enterPublicInterface())
				return;
			if (isConnectionAllowed("localhost", port))
				return;
			throw new SecurityException(formatLocalized("security.error_network_listen", port)); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkMulticast(InetAddress maddr) {
		try {
			if (enterPublicInterface())
				return;
			throw new SecurityException(localized("security.error_network_multicast")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkExec(String cmd) {
		try {
			if (enterPublicInterface())
				return;
			throw new SecurityException(localized("security.error_execute")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkPrintJobAccess() {
		try {
			if (enterPublicInterface())
				return;
			throw new SecurityException(localized("security.error_printer")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkCreateClassLoader() {
		try {
			if (enterPublicInterface())
				return;
			checkForNonWhitelistedStackFrames(() -> localized("security.error_classloader")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkAccess(Thread t) {
		ThreadGroup tg = t.getThreadGroup();
		try {
			if (enterPublicInterface())
				return;
			super.checkAccess(t);
			// Thread terminated
			if (tg == null)
				return;
			if (!testThreadGroup.parentOf(tg))
				checkForNonWhitelistedStackFrames(() -> localized("security.error_thread_access")); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkAccess(ThreadGroup g) {
		try {
			if (enterPublicInterface())
				return;
			super.checkAccess(g);
			if (!testThreadGroup.parentOf(g))
				checkForNonWhitelistedStackFrames(() -> localized("security.error_threadgroup_access")); //$NON-NLS-1$
			checkThreadCreation();
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkPackageDefinition(String pkg) {
		try {
			if (enterPublicInterface())
				return;
			LOG.info("PKG-DEF: {}", pkg); //$NON-NLS-1$
			super.checkPackageDefinition(pkg);
			if (SecurityConstants.STACK_WHITELIST.stream().anyMatch(pkg::startsWith))
				throw new SecurityException(formatLocalized("security.error_package_definition", pkg)); //$NON-NLS-1$
		} finally {
			exitPublicInterface();
		}
	}

	@Override
	public void checkPermission(Permission perm) {
		String permName = perm.getName();
		String permActions = perm.getActions();
		String permString = String.valueOf(perm);
		try {
			if (enterPublicInterface())
				return;
			// for files: read, readlink, write, delete
			// for threads: modifyThread
			// for preferences: preferences
			// for redefinition of IO: setIO
			var whitelist = List.of("getClassLoader", "accessSystemModules"); //$NON-NLS-1$ //$NON-NLS-2$
			if (whitelist.contains(permName))
				return;
			var blacklist = List.of("manageProcess", "shutdownHooks", "createSecurityManager"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (blacklist.contains(permName))
				throw new SecurityException(localized("security.error_blacklist") + permString); //$NON-NLS-1$
			if ("setIO".equals(permName)) // $NON-NLS-1$
				checkForNonWhitelistedStackFrames(() -> localized("security.error_blacklist") + permString); //$NON-NLS-1$
			// this could be removed / reduced, if the specified part is needed
			if ("setSecurityManager".equals(permName) && !isPartlyDisabled) //$NON-NLS-1$
				throw new SecurityException(localized("security.error_security_manager")); //$NON-NLS-1$
			if (perm instanceof SerializablePermission)
				throw new SecurityException(localized("security.error_modify_serialization") + permString); //$NON-NLS-1$
			if (perm instanceof AWTPermission)
				throw new SecurityException(localized("security.error_awt") + permString); //$NON-NLS-1$
			if (perm instanceof ManagementPermission)
				checkForNonWhitelistedStackFrames(() -> localized("security.error_management") + permString); //$NON-NLS-1$
			if ((configuration == null || configuration.allowedLocalPort().isEmpty())
					&& (perm instanceof NetPermission || perm instanceof SocketPermission))
				throw new SecurityException(localized("security.error_networking") + permString); //$NON-NLS-1$
			if (perm instanceof SecurityPermission) {
				if (permName.startsWith("getPolicy") || permName.startsWith("getProperty")) //$NON-NLS-1$ //$NON-NLS-2$
					return;
				checkForNonWhitelistedStackFrames(() -> localized("security.error_modify_security") + permString); //$NON-NLS-1$
			}
			if (perm instanceof SSLPermission)
				throw new SecurityException(localized("security.error_modify_ssl") + permString); //$NON-NLS-1$
			if (perm instanceof AuthPermission)
				throw new SecurityException(localized("security.error_modify_auth") + permString); //$NON-NLS-1$
			if (perm instanceof FilePermission)
				checkPathAccess(Path.of(permName), PathActionLevel.getLevelOf(permActions));
		} finally {
			exitPublicInterface();
		}
	}

	private void checkPathAccess(Path p, PathActionLevel pathActionLevel) {
		boolean whitelisted = false;
		boolean blacklisted = false;
		try {
			Path pa = p.toAbsolutePath();
			blacklisted = isPathBlacklisted(pa, pathActionLevel);
			whitelisted = isPathWhitelisted(pa, pathActionLevel);
			if (!blacklisted && whitelisted)
				return;
		} catch (Exception e) {
			LOG.warn("Error in checkPathAccess", e);
		}
		String message = String.format("BAD PATH ACCESS: %s (BL:%s, WL:%s)", p, blacklisted, whitelisted); //$NON-NLS-1$
		checkForNonWhitelistedStackFrames(() -> {
			LOG.warn(message);
			return formatLocalized("security.error_path_access", p); //$NON-NLS-1$
		});
	}

	private boolean isPathWhitelisted(Path pa, PathActionLevel pathActionLevel) {
		var pathWhitelist = configuration.whitelistedPaths();
		if (pathWhitelist.isEmpty())
			return pa.startsWith(configuration.executionPath());
		return pathWhitelist.get().stream().anyMatch(pm -> pm.matchesWithLevel(pa, pathActionLevel));
	}

	private boolean isPathBlacklisted(Path pa, PathActionLevel pathActionLevel) {
		var pathBlacklist = configuration.blacklistedPaths();
		return pathBlacklist.stream().anyMatch(pm -> pm.matchesWithLevel(pa, pathActionLevel));
	}

	@Override
	public void checkPackageAccess(String pkg) {
		try {
			if (enterPublicInterface())
				return;
			if (SecurityConstants.PACKAGE_USE_BLACKLIST.stream().anyMatch(pkg::startsWith)) {
				/*
				 * this is a very expensive operation, can we do better? (no)
				 */
				checkForNonWhitelistedStackFrames(() -> formatLocalized("security.error_disallowed_package", pkg)); //$NON-NLS-1$
			}
			super.checkPackageAccess(pkg);
		} finally {
			exitPublicInterface();
		}
	}

	private void checkForNonWhitelistedStackFrames(Supplier<String> message) {
		var nonWhitelisted = getNonWhitelistedStackFrames();
		if (!nonWhitelisted.isEmpty()) {
			LOG.warn("NWSFs ==> {}", nonWhitelisted); //$NON-NLS-1$
			var first = nonWhitelisted.get(0);
			throw new SecurityException(formatLocalized("security.stackframe_add_info", message.get(), //$NON-NLS-1$
					first.getLineNumber(), first.getFileName()));
		}
	}

	private List<StackFrame> getNonWhitelistedStackFrames() {
		if (isCurrentThreadWhitelisted()) {
			return stackWalker
					.walk(sfs -> sfs.filter(this::isStackFrameNotWhitelisted).distinct().collect(Collectors.toList()));
		}
		return stackWalker.walk(sfs -> sfs.collect(Collectors.toList()));
	}

	private boolean isCallNotWhitelisted(String call) {
		return SecurityConstants.STACK_BLACKLIST.stream().anyMatch(call::startsWith)
				|| (SecurityConstants.STACK_WHITELIST.stream().noneMatch(call::startsWith)
						&& (configuration == null || !configuration.whitelistedClassNames().contains(call)));
	}

	private boolean isStackFrameNotWhitelisted(StackFrame sf) {
		return isCallNotWhitelisted(sf.getClassName());
	}

	private boolean isStackFrameNotWhitelisted(StackTraceElement ste) {
		return isCallNotWhitelisted(ste.getClassName());
	}

	public static Optional<StackTraceElement> firstNonWhitelisted(StackTraceElement... elements) {
		for (StackTraceElement ste : elements) {
			if (INSTANCE.isStackFrameNotWhitelisted(ste))
				return Optional.ofNullable(ste);
		}
		return Optional.empty();
	}

	private static boolean isLocalHost(String host) {
		return Objects.equals(host, "localhost") || Objects.equals(host, "127.0.0.1"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private boolean isConnectionAllowed(String host, int port) {
		var nwsfs = getNonWhitelistedStackFrames();
		LOG.info("Connection use request: {}:{} [NWSFs: {}]", host, port, nwsfs.size()); //$NON-NLS-1$
		if (nwsfs.isEmpty())
			return true;
		return configuration != null && isLocalHost(host)
				&& (configuration.allowedLocalPort().equals(OptionalInt.of(port)) || port == -1);
	}

	@Override
	public ThreadGroup getThreadGroup() {
		return testThreadGroup;
	}

	@SuppressWarnings("deprecation")
	private Thread[] checkThreadGroup() {
		blockThreadCreation = true;
		int originalCount = testThreadGroup.activeCount();
		if (originalCount == 0)
			return new Thread[0]; // everything ok
		Thread[] threads = new Thread[originalCount];
		testThreadGroup.enumerate(threads);
		// try gentle shutdown; without that, runs on CI might fail because of previous.
		for (Thread thread : threads) {
			if (thread == null)
				continue;
			try {
				thread.interrupt();
				thread.join(500 / originalCount + 1L);
				LOG.debug("State {} after interrupt and join of {}", thread.getState(), thread); //$NON-NLS-1$
			} catch (@SuppressWarnings("unused") InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (testThreadGroup.activeCount() == 0)
			return new Thread[0];
		// try forceful shutdown
		SecurityException exception = new SecurityException(
				formatLocalized("security.error_threads_not_stoppable", Arrays.toString(threads))); //$NON-NLS-1$
		int alive = threads.length;
		TRIES: for (int i = 0; i < 50 && alive > 0; i++) {
			alive = 0;
			for (Thread thread : threads) {
				if (thread == null || !thread.isAlive())
					continue;
				alive++;
				LOG.debug("Try {} to stop {}, state: {}", i + 1, thread, thread.getState()); //$NON-NLS-1$
				/*
				 * we definitely want to forcefully terminate all threads (otherwise, next tests
				 * will fail)
				 */
				thread.stop();
				try {
					thread.join(20 / originalCount + 1L);
				} catch (InterruptedException e) {
					LOG.warn("Error in checkThreadGroup 1", e);
					exception.addSuppressed(e);
					Thread.currentThread().interrupt();
					break TRIES;
				}
			}
		}
		for (Thread thread : threads) {
			if (thread == null || !thread.isAlive())
				continue;
			try {
				thread.join(100 / originalCount + 1L);
			} catch (InterruptedException e) {
				LOG.warn("Error in checkThreadGroup 2", e);
				exception.addSuppressed(e);
				Thread.currentThread().interrupt();
				break;
			}
			if (thread.getState() != State.TERMINATED)
				LOG.error("THREAD STOP ERROR: Thread {} is still in state {}", thread, thread.getState()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (testThreadGroup.activeCount() > 0)
			throw exception;
		return threads;
	}

	private void checkThreadCreation() {
		if (blockThreadCreation || configuration == null || configuration.allowedThreadCount().isEmpty()) {
			checkForNonWhitelistedStackFrames(() -> localized("security.error_thread_access")); //$NON-NLS-1$
			return;
		}
		int current = testThreadGroup.activeCount();
		int max = configuration.allowedThreadCount().getAsInt();
		if (max < current)
			checkForNonWhitelistedStackFrames(() -> formatLocalized("security.error_thread_maxExceeded", current, max)); //$NON-NLS-1$
	}

	private boolean isCurrentThreadWhitelisted() {
		Thread current = Thread.currentThread();
		String name;
		try {
			exitPublicInterface();
			name = current.getName();
		} finally {
			enterPublicInterface();
		}
		/*
		 * NOTE: the order is very important here!
		 */
		var blacklist = Set.of("Finalizer", "InnocuousThread"); //$NON-NLS-1$ //$NON-NLS-2$
		if (blacklist.stream().anyMatch(name::startsWith))
			return false;
		if (!testThreadGroup.parentOf(current.getThreadGroup()))
			return true;
		return whitelistedThreads.stream().anyMatch(t -> t.equals(current));
	}

	private void whitelistThread(Thread t) {
		LOG.info("Request whitelisting: {} {}", t, INSTANCE.whitelistedThreads); //$NON-NLS-1$
		boolean whitelisted = isCurrentThreadWhitelisted();
		if (!whitelisted)
			throw new SecurityException(localized("security.error_thread_whitelisting_failed")); //$NON-NLS-1$
		whitelistedThreads.add(t);
		LOG.info("Thread whitelisted: {}", t); //$NON-NLS-1$
	}

	private void unwhitelistThreads() {
		whitelistedThreads.clear();
	}

	static boolean isStaticWhitelisted(String name) {
		return SecurityConstants.STACK_WHITELIST.stream().anyMatch(name::startsWith);
	}

	public static synchronized boolean isInstalled() {
		return System.getSecurityManager() instanceof ArtemisSecurityManager;
	}

	public static synchronized String install(ArtemisSecurityConfiguration configuration) {
		if (INSTANCE.lastUninstallFailed) {
			LOG.info("Try recovery from lastUninstallFailed"); //$NON-NLS-1$
			INSTANCE.checkThreadGroup();
			INSTANCE.isPartlyDisabled = true;
			System.setSecurityManager(ORIGINAL);
			INSTANCE.isPartlyDisabled = false;
			INSTANCE.lastUninstallFailed = false;
		} else if (isInstalled()) {
			throw new IllegalStateException(localized("security.already_installed")); //$NON-NLS-1$
		}
		if (LOG.isInfoEnabled())
			LOG.info("Request install with {}", configuration.shortDesc()); //$NON-NLS-1$ //$NON-NLS-2$
		String token = INSTANCE.generateAccessToken();
		INSTANCE.blockThreadCreation = false;
		System.setSecurityManager(INSTANCE);
		INSTANCE.configuration = Objects.requireNonNull(configuration);
		INSTANCE.unwhitelistThreads();
		return token;
	}

	public static synchronized void uninstall(String accessToken) {
		if (!isInstalled())
			throw new IllegalStateException(localized("security.not_installed")); //$NON-NLS-1$
		Thread[] active = new Thread[0];
		int oldPrio = Thread.currentThread().getPriority();
		try {
			INSTANCE.checkAccess(accessToken);
			if (INSTANCE.isPartlyDisabled)
				throw new IllegalStateException(localized("security.already_disabled")); //$NON-NLS-1$

			Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
			LOG.info("Request uninstall"); //$NON-NLS-1$
			// try to clean up and try to run finalize() of test objects
			System.gc();
			System.runFinalization();
			// cannot be used in conjunction with classic JUnit timeout, use @StrictTimeout
			active = INSTANCE.checkThreadGroup();
			INSTANCE.unwhitelistThreads();
			INSTANCE.isPartlyDisabled = true;
			System.setSecurityManager(ORIGINAL);
			INSTANCE.isPartlyDisabled = false;
			INSTANCE.configuration = null;
			INSTANCE.lastUninstallFailed = false;
		} catch (Throwable t) {
			INSTANCE.lastUninstallFailed = true;
			LOG.error("UNINSTALL FAILED", t); //$NON-NLS-1$
			throw t;
		} finally {
			Thread.currentThread().setPriority(oldPrio);
		}
		if (active.length > 0)
			throw new IllegalStateException(
					formatLocalized("security.error_threads_still_active", Arrays.toString(active))); //$NON-NLS-1$
	}

	public static synchronized void configure(String accessToken, ArtemisSecurityConfiguration configuration) {
		INSTANCE.checkAccess(accessToken);
		INSTANCE.configuration = configuration;
	}

	public static synchronized void requestThreadWhitelisting(Thread t) {
		INSTANCE.whitelistThread(t);
	}

	private static String hash(String s) {
		if (s == null)
			return ""; //$NON-NLS-1$
		return Base64.getEncoder().encodeToString(SHA256.digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	public static BiConsumer<String, Object> getOnModification() {
		return ON_MODIFICATION;
	}
}

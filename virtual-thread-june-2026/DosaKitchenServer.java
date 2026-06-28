import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

enum Mode {OLD, NEW}

public class DosaKitchenServer {
	static final int CITY_STOVE_LIMIT = 8;
	static final int COLD_STORE_PORT = 8080;

	public static void main(String[] args) throws IOException, InterruptedException {
		var mode = Mode.NEW;
		for (int i = 0; i < args.length - 1; i++) {
			if ("--mode".equals(args[i])) {
				mode = Mode.valueOf(args[i + 1]);
			}
		}
		if (!mode.equals(Mode.OLD) && !mode.equals(Mode.NEW)) {
			System.err.println("Usage: java DosaKitchenServer.java --mode [old|new]");
			System.exit(1);
		}
		new ColdStore().start();
		new Kitchen(mode).start();
		System.out.println();
		new CountDownLatch(1).await();
	}
}

class ColdStore {
	static final int STORE_FETCH_MS = 300;

	void start() throws IOException {
		int maxWorkers = DosaKitchenServer.CITY_STOVE_LIMIT * 10;
		ExecutorService staff = new ThreadPoolExecutor(maxWorkers, maxWorkers, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
			Thread t = new Thread(r, "store-staff-" + new AtomicInteger().incrementAndGet());
			t.setDaemon(true);
			return t;
		});

		ServerSocket serverSocket = new ServerSocket(DosaKitchenServer.COLD_STORE_PORT);
		Thread.ofPlatform().name("cold-store-listener").daemon(true).start(() -> {
			System.out.printf("[Cold store] port %d | fetch time: %dms%n%n", DosaKitchenServer.COLD_STORE_PORT, STORE_FETCH_MS);
			try {
				while (true) {
					var socket = serverSocket.accept();
					staff.submit(() -> fulfill(socket));
				}
			} catch (IOException e) {
				e.printStackTrace();
			}

		});
	}

	private void fulfill(Socket caller) {
		try (caller) {
			byte[] buf = new byte[256];
			int n = caller.getInputStream().read(buf);
			String order = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "unknown";

			Thread.sleep(STORE_FETCH_MS); // walk to the refrigerated section

			caller.getOutputStream().write(("FULFILLED: " + order + "\n").getBytes(StandardCharsets.UTF_8));
			caller.getOutputStream().flush();
		} catch (Exception ignored) {
		}
	}
}

class Kitchen {
	static final int KITCHEN_PORT = 8081;
	static final Method M_CARRIER;
	private static final ConcurrentHashMap<String, String> carrierAliases = new ConcurrentHashMap<>();
	private static final AtomicInteger carrierCounter = new AtomicInteger(0);
	private final AtomicInteger orderSeq = new AtomicInteger(0);
	private final AtomicInteger sameChefs = new AtomicInteger(0);
	private final AtomicInteger diffChefs = new AtomicInteger(0);
	private final ConcurrentHashMap<String, AtomicInteger> chefUsage = new ConcurrentHashMap<>();
	private final Mode mode;
	private final ExecutorService chefs;

	static {
		try {
			M_CARRIER = Thread.class.getDeclaredMethod("currentCarrierThread");
			M_CARRIER.setAccessible(true);
		} catch (Exception e) {
			throw new ExceptionInInitializerError("Requires: --add-opens java.base/java.lang=ALL-UNNAMED");
		}
	}

	Kitchen(Mode mode) {
		this.mode = mode;
		AtomicInteger chefNum = new AtomicInteger(0);
		chefs = Mode.OLD.equals(mode) ? Executors.newFixedThreadPool(DosaKitchenServer.CITY_STOVE_LIMIT * 2, r -> new Thread(r, "chef-" + chefNum.incrementAndGet())) :
				Executors.newVirtualThreadPerTaskExecutor();
	}

	void start() throws IOException {
		var server = new ServerSocket(KITCHEN_PORT);
		System.out.printf("[Kitchen] Mode=%s | port %d%n%n", mode, KITCHEN_PORT);
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("kitchen-shutdown").unstarted(this::printLifecycleSummary));

		Thread.ofPlatform().name("kitchen-listener").daemon(true).start(() -> {
			while (!server.isClosed()) {
				try {
					var customer = server.accept();
					int order = orderSeq.incrementAndGet();
					chefs.submit(() -> {
						takeOrderOf(customer, order);
					});
				} catch (IOException e) {
					if (!e.getMessage().contains("closed")) {
						System.out.println("[Kitchen] Error: " + e.getMessage());
					}
				}
			}
		});
	}

	private void takeOrderOf(Socket customer, int order) {
		try (customer) {
			drainHttpRequest(customer);

			if (Mode.NEW.equals(mode)) Thread.currentThread().setName("recipe-" + order);
			String chefBefore = currentChef();
			log("#Order " + order + " received → calling cold store");
			var ingredient = getIngredientFor(order);
			String chefAfter = currentChef();

			chefUsage.computeIfAbsent(chefBefore, k -> new AtomicInteger()).incrementAndGet();
			if (!chefBefore.equals(chefAfter)) {
				chefUsage.computeIfAbsent(chefAfter, k -> new AtomicInteger()).incrementAndGet();
			}

			logChefHandlingFor(order, chefBefore, chefAfter);
			final var threadInfo = getThreadInfo(chefBefore, chefAfter);
			writeHttpResponse(customer, buildResponse(mode.name() + " mode", order, threadInfo, ingredient));
		} catch (Exception e) {
			System.err.println("order-" + order + " error: " + e.getMessage());
		}
	}

	private void logChefHandlingFor(int order, String chefBefore, String chefAfter) {
		if (Mode.NEW.equals(mode)) {
			boolean rotated = !chefBefore.equals(chefAfter);
			if (rotated) {
				diffChefs.incrementAndGet();
				log("#order " + order + " returned  → chef rotated: " + chefBefore + " → " + chefAfter);
			} else {
				sameChefs.incrementAndGet();
				log("#order " + order + " returned  → same chef (carrier was idle)");
			}
		} else {
			log("#" + order + " returned  → cooking on same thread");
		}
	}

	private String getThreadInfo(String chefBefore, String chefAfter) {
		return Mode.OLD.equals(mode) ? Thread.currentThread().getName() : "chef before: " + chefBefore + " | chef after: " + chefAfter + " | rotated: " + !chefBefore.equals(chefAfter);
	}

	private void printLifecycleSummary() {
		int total = orderSeq.get();
		int same = sameChefs.get();
		int diff = diffChefs.get();
		String bar = "━".repeat(48);
		System.out.println("\n" + bar);
		System.out.printf(" Kitchen summary — %s mode%n", mode);
		System.out.println(bar);
		System.out.printf(" Total orders  : %d%n", total);
		if (Mode.NEW.equals(mode)) {
			System.out.printf(" Same chef     : %3d  (%d%%)%n", same, total > 0 ? same * 100 / total : 0);
			System.out.printf(" Rotated chef  : %3d  (%d%%)%n", diff, total > 0 ? diff * 100 / total : 0);
		}
		System.out.println();
		System.out.printf(" %-36s %s%n", "Chef", "touches");
		System.out.println(" " + "─".repeat(45));
		chefUsage.entrySet().stream()
				.sorted((a, b) -> b.getValue().get() - a.getValue().get())
				.forEach(e -> System.out.printf(" %-36s %d%n", e.getKey(), e.getValue().get()));
		System.out.println(bar);
	}

	private String getIngredientFor(int order) {
		String ingredient = "";
		try (Socket storeCall = new Socket("localhost", DosaKitchenServer.COLD_STORE_PORT)) {
			storeCall.setSoTimeout(5_000);
			storeCall.getOutputStream().write(("chicken for order-" + order).getBytes(StandardCharsets.UTF_8));
			storeCall.getOutputStream().flush();

			byte[] buf = new byte[256];
			int n = storeCall.getInputStream().read(buf);
			ingredient = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "(nothing)";
		} catch (Exception e) {
			System.out.println("order-" + order + ": ERROR fetching ingredient: " + e.getMessage());
		}
		return ingredient;
	}

	static String buildResponse(String label, int order, String threadInfo, String ingredient) {
		return "Sheldon Dosa House — ₹270\n" + "Mode     : " + label + "\n" + "Order    : " + order + "\n" + "Thread   : " + threadInfo + "\n" + "Received : " + ingredient + "\n";
	}

	static void writeHttpResponse(Socket socket, String body) throws IOException {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		var header = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain; charset=utf-8\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Connection: close\r\n\r\n";
		OutputStream out = socket.getOutputStream();
		out.write(header.getBytes(StandardCharsets.UTF_8));
		out.write(bodyBytes);
		out.flush();
	}

	void log(String msg) {
		if (Mode.NEW.equals(mode)) {
			System.out.printf("[task=%-4s | handler=%-4s] %s%n", Thread.currentThread().getName(), currentChef(), msg);
		} else {
			System.out.printf("[%-20s] %s%n", Thread.currentThread().getName(), msg);
		}
	}

	static String currentChef() {
		try {
			Thread ct = (Thread) M_CARRIER.invoke(null);
			if (ct == null) {
				return "n/a";
			}
			if (Thread.currentThread().isVirtual()) {
				return carrierAliases.computeIfAbsent(ct.getName(), k -> "chef-" + carrierCounter.incrementAndGet());
			}
			return ct.getName();
		} catch (Exception e) {
			return "?";
		}
	}

	static void drainHttpRequest(Socket socket) throws IOException {
		InputStream raw = socket.getInputStream();
		int[] last = {0, 0, 0, 0};
		int b;
		while ((b = raw.read()) != -1) {
			last[0] = last[1];
			last[1] = last[2];
			last[2] = last[3];
			last[3] = b;
			if (last[0] == '\r' && last[1] == '\n' && last[2] == '\r' && last[3] == '\n') break;
		}
	}
}

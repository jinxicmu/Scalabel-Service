import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/* Sample code for basic Server */

public class Server extends UnicastRemoteObject implements IServer {
	static List<Cloud.FrontEndOps.Request> requestQueue = new LinkedList<Cloud.FrontEndOps.Request>();
	static List<Integer> readyAppServers = new LinkedList<Integer>();
	static List<String> ids = new LinkedList<String>();
	static List<Integer> Request_ids = new LinkedList<Integer>();
	static List<Integer> App_ids = new LinkedList<Integer>();
	final static int vm_limit = 11;
	static long last_time_scalein = 0;
	static long start_time = 0;

	static final int[] nums_appserver_array = { 2, 2, 2, 2, 2, 2, 2, 2, // 0-7
			2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // 8-19
			4, 4, 4, 4 }; // 20-23
	static final int[] nums_requestserver_array = { 0, 0, 0, 0, 0, 0, 0, 0, // 0-7
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 8-19
			1, 1, 1, 1 }; // 20-23

	public static void main(String args[]) throws Exception {
		int launch_id;
		int kill_id;
		int current_time;
		int nums_appserver = 4;
		int nums_requestserver = 1;
		boolean is_step_up = true;
		if (args.length != 2)
			throw new Exception("Need 2 args: <cloud_ip> <cloud_port>");
		ServerLib SL = new ServerLib(args[0], Integer.parseInt(args[1]));

		/* master server behavior */

		if (SL.getStatusVM(2).equals(
				Cloud.CloudOps.VMStatus.valueOf("NonExistent"))) {
			/* the add request thread */
			Thread thread0 = new Thread(new masterGetRequest(args, SL));
			thread0.start();
			current_time = (int) SL.getTime();
			// decide the initial number of servers by current time
			nums_appserver = nums_appserver_array[current_time];
			nums_requestserver = nums_requestserver_array[current_time];
			// decide whether there exist step up
			if (SL.getTime() == 18)
				is_step_up = false;
			/* create app server */
			for (int i = 0; i < nums_appserver; i++) {
				launch_id = SL.startVM();
				ids.add("AppServer" + " " + launch_id);
				App_ids.add(launch_id);
			}
			/* create request server */
			for (int i = 0; i < nums_requestserver; i++) {
				launch_id = SL.startVM();
				ids.add("RequestServer" + " " + launch_id);
				Request_ids.add(launch_id);
			}
			registerServer(args);

			SL.register_frontend();
			/* the dynamic change number of server */
			Thread thread1 = new Thread(new dynamicChangeServer(SL));
			thread1.start();

			Thread thread2 = new Thread(new prepareCache(args));
			thread2.start();

			start_time = System.currentTimeMillis();
			while (true) {
				Thread.sleep(10);
				/* drop the beginning request since the VM is booting */
				if (System.currentTimeMillis() - start_time <= 4800) {
					while (requestQueue.size() > 0) {
						Cloud.FrontEndOps.Request r = requestQueue.get(0);
						requestQueue.remove(0);
						SL.drop(r);
					}
				}
				if (requestQueue.size() > 0) {
					if (readyAppServers.size() > 0) {
						Integer integer = readyAppServers.get(0);
						readyAppServers.remove(0);
						synchronized (integer) {
							integer.notify();
						}
					}
				}
				// if this is step_up case, then do corresponding modify
				if (!is_step_up
						&& System.currentTimeMillis() - start_time >= 26000) {
					launch_id = SL.startVM();
					ids.add("RequestServer" + " " + launch_id);
					Request_ids.add(launch_id);
					is_step_up = true;
					/* create app server */
					for (int i = 0; i < 2; i++) {
						launch_id = SL.startVM();
						ids.add("AppServer" + " " + launch_id);
						App_ids.add(launch_id);
					}
				}
			}
		}
		/* slaves servers behavior */
		else {
			IServer server = null;
			while ((server = getServerInstance(args[0],
					Integer.parseInt(args[1]))) == null)
				;
			String status_id = server.getId();
			String[] tmp = status_id.split(" ");
			int id = Integer.parseInt(tmp[1]);

			/* RequestServer behavior */

			if (status_id.startsWith("Request")) {
				SL.register_frontend();
				while (true) {
					Cloud.FrontEndOps.Request r = SL.getNextRequest();
					server.pushNextRequestToMaster(r);
				}
			}

			/* AppServer behavior */

			else if (status_id.startsWith("App")) {
				Cloud.DatabaseOps mydb = SL.getDB();
				ICache cache = null;
				List<Long> wait_time = new LinkedList<Long>();
				long sum = 0;
				long count = 0;
				while ((cache = getCacheInstance(args[0],
						Integer.parseInt(args[1]))) == null)
					;
				while (true) {
					long before = System.currentTimeMillis();
					Cloud.FrontEndOps.Request r = server
							.getNextRequestfromMaster(id);
					long after = System.currentTimeMillis();

					if (r.isPurchase) {
						SL.processRequest(r);
					}
					/* try mycache */
					else {
						String key = r.item;
						if (cache.get(key) != null)
							SL.processRequest(r, cache);
						else {/* cache miss */
							String item = mydb.get(key);
							if (item.equals("ITEM")) {
								String price = mydb.get(key + "_price");
								String qty = mydb.get(key + "_qty");
								cache.setThreeTogether(key, item, price, qty,
										"XJ");
							} else {
								cache.set(key, item, "XJ");
							}
							SL.processRequest(r, cache);
						}
					}

					// scale in
					if (count < 6) {
						count++;
						wait_time.add(after - before);
						sum += (after - before);
					} else {
						if (sum >= 1400) {
							if (server.askForMasterWhenScalein())
								System.exit(0);
						}
						long deleted = wait_time.remove(0);
						long latest = after - before;
						wait_time.add(latest);
						sum -= deleted;
						sum += latest;
					}
				}
			}

		}
	}

	public static ICache getCacheInstance(String ip, int port) {
		String url = String.format("//%s:%d/CacheService", ip, port);
		try {
			return (ICache) Naming.lookup(url);
		} catch (MalformedURLException e) {
			System.err.println("Bad URL" + e);
		} catch (RemoteException e) {
			System.err.println("Remote connection refused to url " + url + " "
					+ e);
		} catch (NotBoundException e) {
		}
		return null;
	}

	public static IServer getServerInstance(String ip, int port) {
		String url = String.format("//%s:%d/ServerService", ip, port);
		try {
			return (IServer) Naming.lookup(url);
		} catch (MalformedURLException e) {
			System.err.println("Bad URL" + e);
		} catch (RemoteException e) {
			System.err.println("Remote connection refused to url " + url + " "
					+ e);
		} catch (NotBoundException e) {
		}
		return null;
	}

	public synchronized void pushNextRequestToMaster(Cloud.FrontEndOps.Request r)
			throws RemoteException {
		try {
			requestQueue.add(r);
		} catch (Exception e) {
			System.out.println("pushNextRequestToMaster: " + e.getMessage());
			e.printStackTrace();
		}
	}

	public synchronized String getId() throws RemoteException {
		try {
			String ret = ids.get(ids.size() - 1);
			ids.remove(ids.size() - 1);
			return ret;
		} catch (Exception e) {
			System.out.println("getId: " + e.getMessage());
			e.printStackTrace();
			return (null);
		}
	}

	public synchronized boolean askForMasterWhenScalein()
			throws RemoteException {
		long cur_tsp = System.currentTimeMillis();
		try {
			if ((cur_tsp - start_time) <= 30000)
				return false;
			if (last_time_scalein != 0 && ((cur_tsp - last_time_scalein) < 500))
				return false;
			else {
				if (Server.App_ids.size() > 1) {
					App_ids.remove(0);
					last_time_scalein = cur_tsp;
					return true;
				} else {
					return false;
				}
			}
		} catch (Exception e) {
			return false;
		}
	}

	public Cloud.FrontEndOps.Request getNextRequestfromMaster(int key)
			throws RemoteException {
		try {
			Integer integer = key;
			readyAppServers.add(integer);
			synchronized (integer) {
				integer.wait();
			}
			Cloud.FrontEndOps.Request r = requestQueue.get(0);
			requestQueue.remove(0);
			return r;
		} catch (Exception e) {
			e.printStackTrace();
			return (null);
		}

	}

	public static void registerServer(String[] args) {
		String ip = args[0];
		int port = Integer.parseInt(args[1]); // you should get port from args

		try {
			// create the RMI registry if it doesn't exist.
			LocateRegistry.createRegistry(port);
		} catch (RemoteException e) {
			System.err.println("Failed to create the RMI registry " + e);
		}

		Server server = null;
		try {
			server = new Server();
		} catch (RemoteException e) {
			System.err.println("Failed to create server " + e);
			System.exit(1);
		}
		try {
			Naming.rebind(String.format("//%s:%d/ServerService", ip, port),
					server);
		} catch (RemoteException e) {
		} catch (MalformedURLException e) {
		}

	}

	public Server() throws RemoteException {
	}
}

class dynamicChangeServer implements Runnable {
	private ServerLib SL;
	final int TIMES = 100;

	dynamicChangeServer(ServerLib SL) {
		this.SL = SL;
	}

	public void run() {
		int launch_id;
		long begintime = System.currentTimeMillis();
		long oldtime = System.currentTimeMillis();
		try {
			Thread.sleep(4000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		while (true) {
			double ave_request_size = 0;
			double ave_appserver_size = 0;

			while (System.currentTimeMillis() - oldtime <= TIMES * 20) {
				try {
					Thread.sleep(20);
					ave_request_size += Server.requestQueue.size();
					ave_appserver_size += Server.readyAppServers.size();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			oldtime = System.currentTimeMillis();
			ave_request_size = ave_request_size / TIMES;
			ave_appserver_size = ave_appserver_size / TIMES;

			// add App servers
			if (ave_request_size > 2.5 && ave_request_size < 7
					&& Server.App_ids.size() < Server.vm_limit) {
				int nums = 1;
				if (System.currentTimeMillis() - begintime < 15 * 1000) {
					nums = 2;
				}
				dropTheAccumulation(SL);
				for (int i = 0; i < nums; i++) {
					launch_id = SL.startVM();
					Server.ids.add("AppServer" + " " + launch_id);
					Server.App_ids.add(launch_id);
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} else if (ave_request_size >= 7
					&& Server.App_ids.size() < Server.vm_limit) {
				int nums = 2;

				if (System.currentTimeMillis() - begintime < 15 * 1000) {
					nums = 3;
				}
				if (System.currentTimeMillis() - begintime > 30 * 1000) {
					nums = 1;
				}
				dropTheAccumulation(SL);
				for (int i = 0; i < nums; i++) {
					launch_id = SL.startVM();
					Server.ids.add("AppServer" + " " + launch_id);
					Server.App_ids.add(launch_id);
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

			}
		}
	}

	public static void dropTheAccumulation(ServerLib SL) {
		while (Server.requestQueue.size() > 3) {
			Cloud.FrontEndOps.Request r = Server.requestQueue.get(0);
			Server.requestQueue.remove(0);
			SL.drop(r);
		}
	}

}

class masterGetRequest implements Runnable {
	private ServerLib SL;

	masterGetRequest(String[] args, ServerLib SL) {
		this.SL = SL;
	}

	public void run() {
		Cloud.FrontEndOps.Request r = null;
		while (true) {
			r = SL.getNextRequest();
			Server.requestQueue.add(r);
		}
	}
}

class prepareCache implements Runnable {
	private String[] args;

	prepareCache(String[] args) {
		this.args = args;
	}

	public void run() {
		Cache.registerCacheDatabase(args);
	}
}

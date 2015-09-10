import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;

public class Cache extends UnicastRemoteObject implements ICache {
	static Map<String, String> text = new HashMap<String, String>();

	protected Cache() throws RemoteException {

	}

	public synchronized String get(java.lang.String key) throws RemoteException {
		if (text.containsKey(key))
			return text.get(key);
		return null;
	}

	public synchronized boolean set(java.lang.String key, java.lang.String val,
			java.lang.String auth) throws RemoteException {
		text.put(key, val);
		return true;
	}

	public synchronized boolean setThreeTogether(String key, String item,
			String price, String qty, java.lang.String auth)
			throws RemoteException {
		text.put(key, item);
		text.put(key.trim() + "_price", price);
		text.put(key.trim() + "_qty", qty);
		return true;
	}

	public boolean transaction(java.lang.String item, float price, int qty)
			throws RemoteException {
		return false;
	}

	public static void registerCacheDatabase(String[] args) {
		String ip = args[0];
		int port = Integer.parseInt(args[1]); // you should get port from args

		try {
			// create the RMI registry if it doesn't exist.
			LocateRegistry.createRegistry(port);
		} catch (RemoteException e) {
		}

		Cache cache = null;
		try {
			cache = new Cache();
		} catch (RemoteException e) {
			System.err.println("Failed to create server " + e);
			System.exit(1);
		}
		try {
			Naming.rebind(String.format("//%s:%d/CacheService", ip, port),
					cache);
		} catch (RemoteException e) {
			System.err.println(e);
		} catch (MalformedURLException e) {
			System.err.println(e);
		}

	}
}

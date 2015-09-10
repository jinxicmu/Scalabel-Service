import java.rmi.RemoteException;


public interface ICache
extends Cloud.DatabaseOps {
	public String get(java.lang.String key) throws RemoteException;
	public boolean set(java.lang.String key, java.lang.String val, java.lang.String auth) throws RemoteException;
	public boolean setThreeTogether(String key, String item, String price, String qty, java.lang.String auth) throws RemoteException;
	public boolean transaction(java.lang.String item, float price, int qty) throws RemoteException;
}


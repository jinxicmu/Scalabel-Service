import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IServer extends Remote {	
	public Cloud.FrontEndOps.Request getNextRequestfromMaster(int key) throws RemoteException;
	public void pushNextRequestToMaster(Cloud.FrontEndOps.Request r) throws RemoteException;
	public String getId() throws RemoteException;
	public boolean askForMasterWhenScalein() throws RemoteException;
}

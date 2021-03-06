package game;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.security.InvalidParameterException;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

import gui.Window;
import message.ClientMessage;
import message.PlayerAction;
import message.ServerMessage;
import model.Address;
import model.Maze;
import model.Player;
import tracker.Tracker;

public class GameNodeImpl extends UnicastRemoteObject implements GameNode {

	/**
	 * Generated ID
	 */
	private static final long serialVersionUID = -206482596196783156L;

	private final static int MESSAGE_SIZE_LIMIT = 20;

	private Address primaryServer;
	private Address backUpServer;

	private boolean isPrimary = false;
	private boolean isBackUp = false;

	private Vector<Address> playersInGame;

	private Tracker tracker;

	private Maze theMaze;
	private Player me;
	private Address here;

	private LinkedBlockingQueue<ClientMessage> messagesFromClient;
	private Thread workerThread;
	private LinkedBlockingQueue<ServerMessage> messagesFromServer;
	private Thread gameThread;
	private Thread pinThread;
	
	private Window gameWindow;

	public GameNodeImpl(Tracker tracker, Vector<Address> playerNameList, int size, int numberOfTreasures,
			Address here) throws RemoteException{

		this.tracker = tracker;
		this.here = here;
		me = new Player(here.getUserName(), 0, null, here);

		theMaze = new Maze(size, numberOfTreasures);
		messagesFromClient = new LinkedBlockingQueue<ClientMessage>(MESSAGE_SIZE_LIMIT);
		workerThread = new Thread(processMessageRunnable);

		playersInGame = new Vector<Address>();
		playersInGame.addAll(playerNameList);

		messagesFromServer = new LinkedBlockingQueue<ServerMessage>();
		gameThread = new Thread(updateMazeRunnable);
		pinThread = new Thread(pinPlayersRunnable);

		if (playerNameList.size() == 1) {
			primaryServer = playerNameList.get(0);
			backUpServer = here;
			isBackUp = true;
			System.out.println("Primary server at " + primaryServer.getKey());
		}

		if (playerNameList.size() >= 2) {
			primaryServer = playerNameList.get(0);
			backUpServer = playerNameList.get(1);
		}
		
	}
	
	public void init() throws RemoteException{
		startProcessingMessages();

		// if this is not the primary server
		// tell the primary server that i have joined
		// and starts the game
		if (playersInGame.size() >= 1) {
			this.playerMadeAMove(PlayerAction.JOIN);
		}
		
		// this node has not been added to the tracker yet
		if (playersInGame.isEmpty()) {
			primaryServer = here;
			isPrimary = true;
			theMaze.initialize();
			addNewPlayer(this);
			
			// start the actual game GUI
			gameWindow = new Window(this);
		}
		
		System.out.println("Successfully started player at [" + here.getKey() +"] with number of players " + playersInGame.size());
	}

	public void startProcessingMessages() {
		workerThread.start();
		gameThread.start();
		if (isPrimary())
			pinThread.start();
	}

	/**
	 * SERVER SIDE STARTS
	 */

	public void syncUpMaze(Maze backUpMaze) {
		theMaze = null;
		theMaze = new Maze(backUpMaze.getSize(), backUpMaze.getNumberOfTreasures());
		theMaze.copyDataFrom(backUpMaze);
	}

	@Override
	public boolean enqueueNewMessage(ClientMessage message) throws RemoteException {
		System.out.println("Got message: " + message.toString());
		return messagesFromClient.offer(message);
	}

	private Runnable processMessageRunnable = new Runnable() {

		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				ClientMessage nextMessage = messagesFromClient.poll();
				if (nextMessage != null) {
					processMessageFromClient(nextMessage);
				}
			}
		}

	};

	private void processMessageFromClient(ClientMessage message) {
		Address target = message.getTargetAddress();
		PlayerAction action = message.getPlayerAction();

		// look up the corresponding player in RMI, and add to the current game
		// return true if succeed
		GameNode playerNode = findGameNode(target);

		if (playerNode == null)
			return;

		switch (action) {
		case JOIN:
			try {
				addNewPlayer(playerNode);
				tellBackUpAndCallingNodesTheNewGameState(target, playerNode);
			} catch (RemoteException e) {
				e.printStackTrace();
				removePlayer(target);
			}
			break;
		case QUIT:
			removePlayer(target);
			break;
		default:
			// MOVE_UP, LEFT, RIGHT, DOWN, STAY
			theMaze.movePlayer(target.getKey(), action);
			tellBackUpAndCallingNodesTheNewGameState(target, playerNode);
			break;
		}
		
		System.out.println("Processed message: " + message.toString());
		
	}
	
	private void tellBackUpAndCallingNodesTheNewGameState(Address target, GameNode callingNode){
		// update the calling player node
		try {
			callingNode.updateGame(new ServerMessage(theMaze));
			
			// update the back up server
			if (!callingNode.getAddress().sameAs(backUpServer))
				updateBackUpServer();
		} catch (RemoteException e) {
			e.printStackTrace();
			removePlayer(target);
		}
		
		// update the local GUI (this is for primary server)
		gameWindow.updateGame(theMaze);
	}

	public boolean isPrimary() {
		return isPrimary;
	}

	public boolean isBackUp() {
		return isBackUp;
	}

	@Override
	public void becomePrimary() {
		isPrimary = true;
		isBackUp = false;
		primaryServer = here;
		findNewBackUp();
		
		if (!pinThread.isAlive())
			pinThread.start();
	}

	@Override
	public void becomeBackUp() {
		isPrimary = false;
		isBackUp = true;
		backUpServer = here;
	}

	/**
	 * Update the back up server with the most up to date game maze This should
	 * only be called by primary server
	 */
	private void updateBackUpServer() {
		GameNode backUpNode = null;
		try {
			backUpNode = (GameNode) LocateRegistry.getRegistry(backUpServer.getHost(), backUpServer.getPort())
					.lookup(backUpServer.getKey());
			backUpNode.updateGame(new ServerMessage(theMaze));

		} catch (RemoteException | NotBoundException e) {
			// in the event that back up server is no where to be found
			System.out.println("BackUp Server has stopped working");
			e.printStackTrace();
			findNewBackUp();
		}
	}

	/**
	 * Original back up server stopped working, find a new one. only be called
	 * by primary server
	 */
	private void findNewBackUp() {
		GameNode backUpNode = null;

		try {
			// find the next available node
			retrieveNodesListFromTracker();
			for (int i = 0; i < playersInGame.size(); i++) {
				Address address = playersInGame.get(i);
				if (!address.sameAs(here) && !address.sameAs(backUpServer)) {
					// if this node is not the primary server, set it is back up
					// server
					backUpServer = address;
					backUpNode = (GameNode) LocateRegistry.getRegistry(backUpServer.getHost(), backUpServer.getPort())
							.lookup(backUpServer.getKey());
					backUpNode.updateGame(new ServerMessage(theMaze));
					backUpNode.becomeBackUp();
					break;
				}
			}
		} catch (NotBoundException | RemoteException e) {
			e.printStackTrace();
			System.out.println("Cannot locate the node, this should not happen if the list is up to date");
		}
	}

	private void retrieveNodesListFromTracker() {
		try {
			Vector<Address> nodes = tracker.getNodes();
			playersInGame.clear();
			playersInGame.addAll(nodes);

		} catch (RemoteException e) {
			e.printStackTrace();
			System.out.println("Tracker has stopped working");
		}

	}

	/**
	 * This is called from processing client message. At this point of time,
	 * tracker should have the address of this new player already
	 * 
	 * @param playerNode
	 * @throws RemoteException
	 */
	private void addNewPlayer(GameNode playerNode) throws RemoteException {
		// tell the local maze
		theMaze.addPlayer(playerNode.getAddress().getKey(), playerNode.getPlayer());
		tracker.addNode(playerNode.getAddress());
		// tracker should have already known that this player joined game
		// but we still update the list just to be safe
		retrieveNodesListFromTracker();
		// if this is primary server and player 2 has joined
		if (playersInGame.size() == 2) {
			backUpServer = playerNode.getAddress();
			playerNode.becomeBackUp();
			System.out.println(playerNode.getAddress().getUserName() + " is now back up");
		}

//		if (!gamePlaying && playersInGame.size() >= 2)
//			this.startProcessingMessages();

		if (theMaze.getPlayers().size() != playersInGame.size()) {
			throw new InvalidParameterException("player size is inconsistent");
		}

	}
	
	/**
	 * Regularly checks whether the players are still in game
	 * The checking interval has been set to 10000ms => 10s
	 */
	private Runnable pinPlayersRunnable = new Runnable() {

		@Override
		public void run() {
			while(true){
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				retrieveNodesListFromTracker();
				
				if (isPrimary() && !playersInGame.get(0).sameAs(here)){
					throw new InvalidParameterException("primary server is not the first node in the tracker list");
				}
				
				if (!playersInGame.get(1).sameAs(backUpServer)){
					throw new InvalidParameterException("backup server is not the second node in the tracker list");
				}
				
				try {
					LocateRegistry.getRegistry(backUpServer.getHost(), backUpServer.getPort()).lookup(backUpServer.getKey());
				} catch (RemoteException | NotBoundException e) {
					// in the event that back up server is no where to be found
					System.out.println("BackUp Server has stopped working");
					e.printStackTrace();
					findNewBackUp();
				}
				
				for (Address address:playersInGame){
					findGameNode(address);
				}
			}
		}
		
	};

	/**
	 * SERVER SIDE ENDS
	 */

	private GameNode findGameNode(Address address) {
		try {
			return (GameNode) LocateRegistry.getRegistry(address.getHost(), address.getPort()).lookup(address.getKey());
		} catch (RemoteException | NotBoundException e) {
			e.printStackTrace();
			// in case the node is not there,
			// (may have crashed,)
			// we remove it from server
			removePlayer(address);
		}
		return null;
	}

	private void removePlayer(Address playerAddress) {
		theMaze.removePlayer(playerAddress.getKey());
		playersInGame.remove(playerAddress);
		// notify the tracker of player leaving
		try {
			tracker.updateNodesList(playersInGame);
		} catch (RemoteException e) {
			e.printStackTrace();
			System.out.println("Tracker has stopped working");
		}
		
		updateBackUpServer();
		
		// update the local GUI (this is for primary server)
		gameWindow.updateGame(theMaze);
	}

	/**
	 * LOCAL STARTS
	 */

	@Override
	public boolean updateGame(ServerMessage message) throws RemoteException {
		return messagesFromServer.offer(message);
	}

	private Runnable updateMazeRunnable = new Runnable() {

		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				ServerMessage nextMessage = messagesFromServer.poll();
				if (nextMessage != null) {
					processMessageFromServer(nextMessage);
				}
			}
		}
	};

	private void processMessageFromServer(ServerMessage nextMessage) {
		// update the local maze
		Maze serverMaze = nextMessage.getTheMaze();
		theMaze.copyDataFrom(serverMaze);

		// notify the GUI of the new game state (this is for nodes other than primary server)
		if (gameWindow == null){
			// start the actual game GUI
			try {
				gameWindow = new Window(this);
			} catch (RemoteException e) {
				// error connecting to the local node
				e.printStackTrace();
				System.out.println("error connecting to the local node");
			}
		}
		gameWindow.updateGame(theMaze);
	}

	@Override
	public Address getAddress() throws RemoteException {
		return here;
	}

	@Override
	public Player getPlayer() throws RemoteException {
		return me;
	}

	public void playerMadeAMove(PlayerAction action) {
			
		ClientMessage message = new ClientMessage(here, action);
		System.out.println(message.toString());
		// tell the primary server
		try {
			GameNode primary = (GameNode) LocateRegistry.getRegistry(primaryServer.getHost(), primaryServer.getPort())
					.lookup(primaryServer.getKey());
			primary.enqueueNewMessage(message);
			if (!primary.isPrimary()){
				primary.becomePrimary();
			}
		} catch (RemoteException | NotBoundException e) {
			// in the event that primary is down
			e.printStackTrace();
			try {
				GameNode backUp = (GameNode) LocateRegistry.getRegistry(backUpServer.getHost(), backUpServer.getPort())
						.lookup(backUpServer.getKey());
				primaryServer = backUpServer;
				backUp.becomePrimary();
				backUp.enqueueNewMessage(message);
			} catch (RemoteException | NotBoundException e1) {
				// and the backUp also failed
				e1.printStackTrace();
				retrieveNodesListFromTracker();
				primaryServer = playersInGame.get(0);
				playerMadeAMove(action);
			}

		}
	}

	/**
	 * LOCAL ENDS
	 */

	public void closeGame() {
		if (isPrimary()) {
			// there will always be at least 2 players
			if (playersInGame.size() > 2) {
				try {
					GameNode backUp = (GameNode) LocateRegistry
							.getRegistry(backUpServer.getHost(), backUpServer.getPort()).lookup(backUpServer.getKey());
					primaryServer = backUpServer;
					backUp.becomePrimary();
					playerMadeAMove(PlayerAction.QUIT);
				} catch (RemoteException | NotBoundException e) {
					e.printStackTrace();
				}
			}
		} else {
			playerMadeAMove(PlayerAction.QUIT);
		}
		workerThread.interrupt();
		gameThread.interrupt();
		pinThread.interrupt();
		// notify GUI
		gameWindow.close();
		
		System.exit(0);
	}

	@Override
	public Maze getMaze() throws RemoteException {
	
		return theMaze;
	}

}

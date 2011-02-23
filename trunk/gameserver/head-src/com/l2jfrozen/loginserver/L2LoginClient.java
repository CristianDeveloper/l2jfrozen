/* This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package com.l2jfrozen.loginserver;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.interfaces.RSAPrivateKey;
import java.util.logging.Logger;

import com.l2jfrozen.Config;
import com.l2jfrozen.crypt.LoginCrypt;
import com.l2jfrozen.crypt.ScrambledKeyPair;
import com.l2jfrozen.gameserver.network.serverpackets.L2GameServerPacket;
import com.l2jfrozen.loginserver.network.serverpackets.L2LoginServerPacket;
import com.l2jfrozen.loginserver.network.serverpackets.LoginFail;
import com.l2jfrozen.loginserver.network.serverpackets.LoginFail.LoginFailReason;
import com.l2jfrozen.loginserver.network.serverpackets.PlayFail;
import com.l2jfrozen.loginserver.network.serverpackets.PlayFail.PlayFailReason;
import com.l2jfrozen.logs.Log;
import com.l2jfrozen.netcore.MMOClient;
import com.l2jfrozen.netcore.MMOConnection;
import com.l2jfrozen.netcore.SendablePacket;
import com.l2jfrozen.util.random.Rnd;

/**
 * Represents a client connected into the LoginServer
 * 
 * @author ProGramMoS
 */

public final class L2LoginClient extends MMOClient<MMOConnection<L2LoginClient>>
{
	private static Logger _log = Logger.getLogger(L2LoginClient.class.getName());

	public static enum LoginClientState
	{
		CONNECTED,
		AUTHED_GG,
		AUTHED_LOGIN
	};

	private LoginClientState _state;

	// Crypt
	private LoginCrypt _loginCrypt;
	private ScrambledKeyPair _scrambledPair;
	private byte[] _blowfishKey;

	private String _account="";
	private int _accessLevel;
	private int _lastServer;
	private boolean _usesInternalIP;
	private SessionKey _sessionKey;
	private int _sessionId;
	private boolean _joinedGS;
	private String _ip;
	private long _connectionStartTime;
	
	/**
	 * @param con
	 */
	public L2LoginClient(final MMOConnection<L2LoginClient> con)
	{
		super(con);
		_state = LoginClientState.CONNECTED;
		final String ip = getConnection().getInetAddress().getHostAddress();
		_ip = ip;
		String[] localip = Config.NETWORK_IP_LIST.split(";");
		for(String oneIp : localip)
		{
			if(ip.startsWith(oneIp) || ip.startsWith("127.0"))
			{
				_usesInternalIP = true;
			}
		}

		_scrambledPair = LoginController.getInstance().getScrambledRSAKeyPair();
		_blowfishKey = LoginController.getInstance().getBlowfishKey();
		_sessionId = Rnd.nextInt(Integer.MAX_VALUE);
		_connectionStartTime = System.currentTimeMillis();
		_loginCrypt = new LoginCrypt();
		_loginCrypt.setKey(_blowfishKey);
		LoginController.getInstance().addLoginClient(this);
//		Closer.getInstance().add(this);
	}

	public String getIntetAddress() {
		return _ip;
	}	
	public boolean usesInternalIP()
	{
		return _usesInternalIP;
	}

	/**
	 * @see com.l2jserver.mmocore.interfaces.MMOClient#decrypt(java.nio.ByteBuffer, int)
	 */
	@Override
	public boolean decrypt(ByteBuffer buf, int size)
	{
		boolean ret = false;
		try
		{
			ret = _loginCrypt.decrypt(buf.array(), buf.position(), size);
			_connectionStartTime = System.currentTimeMillis();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			super.getConnection().close((SendablePacket<L2LoginClient>)null);
			return false;
		}

		if(!ret)
		{
			byte[] dump = new byte[size];
			System.arraycopy(buf.array(), buf.position(), dump, 0, size);
			_log.warning("Wrong checksum from client: " + toString());
			super.getConnection().close((SendablePacket<L2LoginClient>)null);
			dump = null;
		}

		return ret;
	}

	/**
	 * @see com.l2jserver.mmocore.interfaces.MMOClient#encrypt(java.nio.ByteBuffer, int)
	 */
	@Override
	public boolean encrypt(ByteBuffer buf, int size)
	{
		final int offset = buf.position();
		try
		{
			size = _loginCrypt.encrypt(buf.array(), offset, size);
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return false;
		}

		buf.position(offset + size);
		return true;
	}

	public LoginClientState getState()
	{
		return _state;
	}

	public void setState(LoginClientState state)
	{
		_state = state;
	}

	public byte[] getBlowfishKey()
	{
		return _blowfishKey;
	}

	public byte[] getScrambledModulus()
	{
		return _scrambledPair._scrambledModulus;
	}

	public RSAPrivateKey getRSAPrivateKey()
	{
		return (RSAPrivateKey) _scrambledPair._pair.getPrivate();
	}

	public String getAccount()
	{
		return _account;
	}

	public void setAccount(String account)
	{
		_account = account;
	}

	public void setAccessLevel(int accessLevel)
	{
		_accessLevel = accessLevel;
	}

	public int getAccessLevel()
	{
		return _accessLevel;
	}

	public void setLastServer(int lastServer)
	{
		_lastServer = lastServer;
	}

	public int getLastServer()
	{
		return _lastServer;
	}

	public int getSessionId()
	{
		return _sessionId;
	}

	public boolean hasJoinedGS()
	{
		return _joinedGS;
	}

	public void setJoinedGS(boolean val)
	{
		_joinedGS = val;
	}

	public void setSessionKey(SessionKey sessionKey)
	{
		_sessionKey = sessionKey;
	}

	public SessionKey getSessionKey()
	{
		return _sessionKey;
	}

	public long getConnectionStartTime()
	{
		return _connectionStartTime;
	}

	public void sendPacket(L2LoginServerPacket lsp)
	{
		if(Config.DEBUG_PACKETS){
			
			Log.add("[ServerPacket] SendingLoginServerPacket, Client: "+this.toString()+" Packet:"+lsp.getType(), "LoginServerPacketsLog");
			
		}
		
		getConnection().sendPacket(lsp);
	}

	public void close(LoginFailReason reason)
	{
		getConnection().close(new LoginFail(reason));
	}

	public void close(PlayFailReason reason)
	{
		getConnection().close(new PlayFail(reason));
	}

	public void close(L2LoginServerPacket lsp)
	{
		getConnection().close(lsp);
	}

	@Override
	public void onDisconnection()
	{
//		Closer.getInstance().close(this);
		if(Config.DEBUG)
		{
			_log.info("DISCONNECTED: " + toString());
		}

		LoginController.getInstance().removeLoginClient(this);
		if(!hasJoinedGS())
		{
			LoginController.getInstance().removeAuthedLoginClient(getAccount());
		}
	}

	@Override
	public String toString()
	{
		InetAddress address = getConnection().getInetAddress();
		if(getState() == LoginClientState.AUTHED_LOGIN)
			return "[" + getAccount() + " (" + (address == null ? "disconnected" : address.getHostAddress()) + ")]";
		else
			return "[" + (address == null ? "disconnected" : address.getHostAddress()) + "]";
	}
	
	@Override
	protected void onForcedDisconnection()
	{
		// Empty
	}
}

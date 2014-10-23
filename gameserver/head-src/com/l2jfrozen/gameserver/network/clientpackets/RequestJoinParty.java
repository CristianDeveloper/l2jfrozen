/*
 * This program is free software; you can redistribute it and/or modify
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
package com.l2jfrozen.gameserver.network.clientpackets;

import org.apache.log4j.Logger;

import com.l2jfrozen.Config;
import com.l2jfrozen.gameserver.model.L2Party;
import com.l2jfrozen.gameserver.model.L2World;
import com.l2jfrozen.gameserver.model.actor.instance.L2PcInstance;
import com.l2jfrozen.gameserver.model.entity.event.CTF;
import com.l2jfrozen.gameserver.model.entity.event.DM;
import com.l2jfrozen.gameserver.model.entity.event.TvT;
import com.l2jfrozen.gameserver.network.SystemMessageId;
import com.l2jfrozen.gameserver.network.serverpackets.AskJoinParty;
import com.l2jfrozen.gameserver.network.serverpackets.SystemMessage;

public final class RequestJoinParty extends L2GameClientPacket
{
	private static Logger LOGGER = Logger.getLogger(RequestJoinParty.class.getClass());
	
	private String _name;
	private int _itemDistribution;
	
	@Override
	protected void readImpl()
	{
		_name = readS();
		_itemDistribution = readD();
	}
	
	@Override
	protected void runImpl()
	{
		L2PcInstance requestor = getClient().getActiveChar();
		L2PcInstance target = L2World.getInstance().getPlayer(_name);
		
		if (requestor == null)
			return;
		
		if (!getClient().getFloodProtectors().getPartyInvitation().tryPerformAction("PartyInvitation"))
		{
			requestor.sendMessage("You Cannot Invite into Party So Fast!");
			return;
		}
		
		if (target == null)
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.TARGET_IS_INCORRECT));
			return;
		}
		
		if ((requestor._inEventDM && (DM.is_teleport() || DM.is_started())) || (target._inEventDM && (DM.is_teleport() || DM.is_started())))
		{
			requestor.sendMessage("You can't invite that player in party!");
			return;
		}
		
		if ((requestor._inEventTvT && !target._inEventTvT && (TvT.is_started() || TvT.is_teleport())) || (!requestor._inEventTvT && target._inEventTvT && (TvT.is_started() || TvT.is_teleport())) || (requestor._inEventCTF && !target._inEventCTF && (CTF.is_started() || CTF.is_teleport())) || (!requestor._inEventCTF && target._inEventCTF && (CTF.is_started() || CTF.is_teleport())))
		{
			requestor.sendMessage("You can't invite that player in party: you or your target are in Event");
			return;
		}
		
		if (target.isInParty())
		{
			SystemMessage msg = new SystemMessage(SystemMessageId.S1_IS_ALREADY_IN_PARTY);
			msg.addString(target.getName());
			requestor.sendPacket(msg);
			return;
		}
		
		if (target == requestor)
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.INCORRECT_TARGET));
			return;
		}
		
		if (target.isCursedWeaponEquiped() || requestor.isCursedWeaponEquiped())
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.INCORRECT_TARGET));
			return;
		}
		
		if (target.isGM() && target.getAppearance().getInvisible())
		{
			requestor.sendMessage("You can't invite GM in invisible mode.");
			return;
		}
		
		if (target.isInJail() || requestor.isInJail())
		{
			SystemMessage sm = SystemMessage.sendString("Player is in Jail");
			requestor.sendPacket(sm);
			return;
		}
		
		if (target.getBlockList().isInBlockList(requestor.getName()))
		{
			SystemMessage sm = new SystemMessage(SystemMessageId.S1_HAS_ADDED_YOU_TO_IGNORE_LIST);
			sm.addString(target.getName());
			requestor.sendPacket(sm);
			return;
		}
		
		if (target.isInOlympiadMode() || requestor.isInOlympiadMode())
			return;
		
		if (target.isInDuel() || requestor.isInDuel())
			return;
		
		if (!requestor.isInParty()) // Asker has no party
		{
			createNewParty(target, requestor);
		}
		else
		// Asker is in party
		{
			if (requestor.getParty().isInDimensionalRift())
			{
				requestor.sendMessage("You can't invite a player when in Dimensional Rift.");
			}
			else
			{
				addTargetToParty(target, requestor);
			}
		}
	}
	
	/**
	 * @param target
	 * @param requestor
	 */
	private void addTargetToParty(L2PcInstance target, L2PcInstance requestor)
	{
		SystemMessage msg;
		
		// summary of ppl already in party and ppl that get invitation
		if (requestor.getParty().getMemberCount() >= 9)
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.PARTY_FULL));
			return;
		}
		
		if (!requestor.getParty().isLeader(requestor))
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.ONLY_LEADER_CAN_INVITE));
			return;
		}
		
		if (requestor.getParty().getPendingInvitation() && !requestor.getParty().isInvitationRequestExpired())
		{
			requestor.sendPacket(new SystemMessage(SystemMessageId.WAITING_FOR_ANOTHER_REPLY));
			return;
		}
		
		if (!target.isProcessingRequest())
		{
			requestor.onTransactionRequest(target);
			target.sendPacket(new AskJoinParty(requestor.getName(), _itemDistribution));
			requestor.getParty().setPendingInvitation(true);
			
			if (Config.DEBUG)
			{
				LOGGER.debug("sent out a party invitation to:" + target.getName());
			}
			
			msg = new SystemMessage(SystemMessageId.YOU_INVITED_S1_TO_PARTY);
			msg.addString(target.getName());
			requestor.sendPacket(msg);
		}
		else
		{
			msg = new SystemMessage(SystemMessageId.S1_IS_BUSY_TRY_LATER);
			msg.addString(target.getName());
			requestor.sendPacket(msg);
			
			if (Config.DEBUG)
			{
				LOGGER.warn(requestor.getName() + " already received a party invitation");
			}
		}
		msg = null;
	}
	
	/**
	 * @param target
	 * @param requestor
	 */
	private void createNewParty(L2PcInstance target, L2PcInstance requestor)
	{
		SystemMessage msg;
		
		if (!target.isProcessingRequest())
		{
			requestor.setParty(new L2Party(requestor, _itemDistribution));
			
			requestor.onTransactionRequest(target);
			target.sendPacket(new AskJoinParty(requestor.getName(), _itemDistribution));
			requestor.getParty().setPendingInvitation(true);
			
			if (Config.DEBUG)
			{
				LOGGER.debug("sent out a party invitation to:" + target.getName());
			}
			
			msg = new SystemMessage(SystemMessageId.YOU_INVITED_S1_TO_PARTY);
			msg.addString(target.getName());
			requestor.sendPacket(msg);
		}
		else
		{
			msg = new SystemMessage(SystemMessageId.S1_IS_BUSY_TRY_LATER);
			msg.addString(target.getName());
			requestor.sendPacket(msg);
			
			if (Config.DEBUG)
			{
				LOGGER.warn(requestor.getName() + " already received a party invitation");
			}
		}
	}
	
	@Override
	public String getType()
	{
		return "[C] 29 RequestJoinParty";
	}
}
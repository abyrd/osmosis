package com.bretth.osmosis.core.mysql.v0_5.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import com.bretth.osmosis.core.domain.v0_5.WayNode;
import com.bretth.osmosis.core.domain.v0_5.Way;
import com.bretth.osmosis.core.mysql.common.DatabaseLoginCredentials;
import com.bretth.osmosis.core.mysql.common.EntityHistory;
import com.bretth.osmosis.core.store.PeekableIterator;
import com.bretth.osmosis.core.store.PersistentIterator;
import com.bretth.osmosis.core.store.ReleasableIterator;


/**
 * Reads all ways from a database ordered by their identifier. It combines the
 * output of the way table readers to produce fully configured way objects.
 * 
 * @author Brett Henderson
 */
public class WayReader implements ReleasableIterator<EntityHistory<Way>> {
	
	private ReleasableIterator<EntityHistory<Way>> wayReader;
	private PeekableIterator<EntityHistory<DBEntityTag>> wayTagReader;
	private PeekableIterator<EntityHistory<DBWayNode>> waySegmentReader;
	private EntityHistory<Way> nextValue;
	private boolean nextValueLoaded;
	
	
	/**
	 * Creates a new instance.
	 * 
	 * @param loginCredentials
	 *            Contains all information required to connect to the database.
	 * @param readAllUsers
	 *            If this flag is true, all users will be read from the database
	 *            regardless of their public edits flag.
	 */
	public WayReader(DatabaseLoginCredentials loginCredentials, boolean readAllUsers) {
		wayReader = new PersistentIterator<EntityHistory<Way>>(
			new WayTableReader(loginCredentials, readAllUsers),
			"way",
			true
		);
		wayTagReader = new PeekableIterator<EntityHistory<DBEntityTag>>(
			new PersistentIterator<EntityHistory<DBEntityTag>>(
				new EntityTagTableReader(loginCredentials, "way_tags"),
				"waytag",
				true
			)
		);
		waySegmentReader = new PeekableIterator<EntityHistory<DBWayNode>>(
			new PersistentIterator<EntityHistory<DBWayNode>>(
				new WayNodeTableReader(loginCredentials),
				"wayseg",
				true
			)
		);
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	public boolean hasNext() {
		if (!nextValueLoaded && wayReader.hasNext()) {
			EntityHistory<Way> wayHistory;
			long wayId;
			int wayVersion;
			Way way;
			List<DBWayNode> wayNodes;
			
			wayHistory = wayReader.next();
			
			way = wayHistory.getEntity();
			wayId = way.getId();
			wayVersion = wayHistory.getVersion();
			
			// Skip all way tags that are from lower id or lower version of the same id.
			while (wayTagReader.hasNext()) {
				EntityHistory<DBEntityTag> wayTagHistory;
				DBEntityTag wayTag;
				
				wayTagHistory = wayTagReader.peekNext();
				wayTag = wayTagHistory.getEntity();
				
				if (wayTag.getEntityId() < wayId) {
					wayTagReader.next();
				} else if (wayTag.getEntityId() == wayId) {
					if (wayTagHistory.getVersion() < wayVersion) {
						wayTagReader.next();
					} else {
						break;
					}
				} else {
					break;
				}
			}
			
			// Load all tags matching this version of the way.
			while (wayTagReader.hasNext() && wayTagReader.peekNext().getEntity().getEntityId() == wayId && wayTagReader.peekNext().getVersion() == wayVersion) {
				way.addTag(wayTagReader.next().getEntity());
			}
			
			// Skip all way segments that are from lower id or lower version of the same id.
			while (waySegmentReader.hasNext()) {
				EntityHistory<DBWayNode> waySegmentHistory;
				DBWayNode waySegment;
				
				waySegmentHistory = waySegmentReader.peekNext();
				waySegment = waySegmentHistory.getEntity();
				
				if (waySegment.getWayId() < wayId) {
					waySegmentReader.next();
				} else if (waySegment.getWayId() == wayId) {
					if (waySegmentHistory.getVersion() < wayVersion) {
						waySegmentReader.next();
					} else {
						break;
					}
				} else {
					break;
				}
			}
			
			// Load all segments matching this version of the way.
			wayNodes = new ArrayList<DBWayNode>();
			while (waySegmentReader.hasNext() && waySegmentReader.peekNext().getEntity().getWayId() == wayId && waySegmentReader.peekNext().getVersion() == wayVersion) {
				wayNodes.add(waySegmentReader.next().getEntity());
			}
			// The underlying query sorts segment references by way id but not
			// by their sequence number.
			Collections.sort(wayNodes, new WayNodeComparator());
			for (WayNode nodeReference : wayNodes) {
				way.addWayNode(nodeReference);
			}
			
			nextValue = wayHistory;
			nextValueLoaded = true;
		}
		
		return nextValueLoaded;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	public EntityHistory<Way> next() {
		EntityHistory<Way> result;
		
		if (!hasNext()) {
			throw new NoSuchElementException();
		}
		
		result = nextValue;
		nextValueLoaded = false;
		
		return result;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	public void remove() {
		throw new UnsupportedOperationException();
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	public void release() {
		wayReader.release();
		wayTagReader.release();
		waySegmentReader.release();
	}
}
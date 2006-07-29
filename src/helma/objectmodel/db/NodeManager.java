/*
 * Helma License Notice
 *
 * The contents of this file are subject to the Helma License
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://adele.helma.org/download/helma/license.txt
 *
 * Copyright 1998-2003 Helma Software. All Rights Reserved.
 *
 * $RCSfile$
 * $Author$
 * $Revision$
 * $Date$
 */

package helma.objectmodel.db;

import helma.framework.core.Application;
import helma.objectmodel.*;
import helma.objectmodel.dom.XmlDatabase;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.File;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The NodeManager is responsible for fetching Nodes from the internal or
 * external data sources, caching them in a least-recently-used Hashtable,
 * and writing changes back to the databases.
 */
public final class NodeManager {

    protected Application app;
    private ObjectCache cache;
    protected IDatabase db;
    protected IDGenerator idgen;
    private boolean logSql;
    private Log sqlLog = null;
    protected boolean logReplication;
    private ArrayList listeners = new ArrayList();

    // a wrapper that catches some Exceptions while accessing this NM
    public final WrappedNodeManager safe;

    /**
     *  Create a new NodeManager for Application app.
     */
    public NodeManager(Application app) {
        this.app = app;
        safe = new WrappedNodeManager(this);
    }

    /**
     * Initialize the NodeManager for the given dbHome and 
     * application properties. An embedded database will be
     * created in dbHome if one doesn't already exist.
     */
    public void init(File dbHome, Properties props)
            throws DatabaseException, ClassNotFoundException,
                   IllegalAccessException, InstantiationException {
        String cacheImpl = props.getProperty("cacheimpl", "helma.util.CacheMap");

        cache = (ObjectCache) Class.forName(cacheImpl).newInstance();
        cache.init(app);

        String idgenImpl = props.getProperty("idGeneratorImpl");

        if (idgenImpl != null) {
            idgen = (IDGenerator) Class.forName(idgenImpl).newInstance();
            idgen.init(app);
        }

        logSql = "true".equalsIgnoreCase(props.getProperty("logsql"));
        logReplication = "true".equalsIgnoreCase(props.getProperty("logReplication"));

        String replicationUrl = props.getProperty("replicationUrl");

        if (replicationUrl != null) {
            if (logReplication) {
                app.logEvent("Setting up replication listener at " + replicationUrl);
            }

            Replicator replicator = new Replicator(this);
            replicator.addUrl(replicationUrl);
            addNodeChangeListener(replicator);
        }

        db = new XmlDatabase();
        db.init(dbHome, app);
    }

    /**
     * Gets the application's root node.
     */
    public Node getRootNode() throws Exception {
        DbMapping rootMapping = app.getRootMapping();
        DbKey key = new DbKey(rootMapping, app.getRootId());
        Node node = getNode(key);
        if (node != null && rootMapping != null) {
            node.setDbMapping(rootMapping);
            node.setPrototype(rootMapping.getTypeName());
        }
        return node;
    }
    /**
     * Checks if the given node is the application's root node.
     */
    public boolean isRootNode(Node node) {
        return app.getRootId().equals(node.getID()) &&
               DbMapping.areStorageCompatible(app.getRootMapping(), node.getDbMapping());
    }

    /**
     *  app.properties file has been updated. Reread some settings.
     */
    public void updateProperties(Properties props) {
        // notify the cache about the properties update
        cache.updateProperties(props);
        logSql = "true".equalsIgnoreCase(props.getProperty("logsql"));
        logReplication = "true".equalsIgnoreCase(props.getProperty("logReplication"));
    }

    /**
     *  Shut down this node manager. This is called when the application 
     *  using this node manager is stopped.
     */
    public void shutdown() throws DatabaseException {
        db.shutdown();

        if (cache != null) {
            cache.shutdown();
            cache = null;
        }

        if (idgen != null) {
            idgen.shutdown();
        }
    }

    /**
     *  Delete a node from the database.
     */
    public void deleteNode(Node node) throws Exception {
        if (node != null) {
            synchronized (this) {
                Transactor tx = (Transactor) Thread.currentThread();

                node.setState(Node.INVALID);
                deleteNode(db, tx.txn, node);
            }
        }
    }

    /**
     *  Get a node by key. This is called from a node that already holds
     *  a reference to another node via a NodeHandle/Key.
     */
    public Node getNode(Key key) throws Exception {
        Transactor tx = (Transactor) Thread.currentThread();

        // See if Transactor has already come across this node
        Node node = tx.getVisitedNode(key);

        if ((node != null) && (node.getState() != Node.INVALID)) {
            return node;
        }

        // try to get the node from the shared cache
        node = (Node) cache.get(key);

        if ((node == null) || (node.getState() == Node.INVALID)) {
            // The requested node isn't in the shared cache.
            if (key instanceof SyntheticKey) {
                Node parent = getNode(key.getParentKey());
                Relation rel = parent.dbmap.getPropertyRelation(key.getID());

                if (rel != null) {
                    return getNode(parent, key.getID(), rel);
                } else {
                    node = null;
                }
            } else if (key instanceof DbKey) {
                node = getNodeByKey(tx.txn, (DbKey) key);
            }

            if (node != null) {
                // synchronize with cache
                synchronized (cache) {
                    Node oldnode = (Node) cache.put(node.getKey(), node);

                    if ((oldnode != null) && !oldnode.isNullNode() &&
                            (oldnode.getState() != Node.INVALID)) {
                        cache.put(node.getKey(), oldnode);
                        node = oldnode;
                    }
                }
                // end of cache-synchronized section
            }
        }

        if (node != null) {
            tx.visitCleanNode(key, node);
        }

        return node;
    }

    /**
     *  Get a node by relation, using the home node, the relation and a key to apply.
     *  In contrast to getNode (Key key), this is usually called when we don't yet know
     *  whether such a node exists.
     */
    public Node getNode(Node home, String kstr, Relation rel)
                 throws Exception {
        if (kstr == null) {
            return null;
        }

        Transactor tx = (Transactor) Thread.currentThread();

        Key key;

        // check what kind of object we're looking for and make an apropriate key
        if (rel.isComplexReference()) {
            // a key for a complex reference
            key = new MultiKey(rel.otherType, rel.getKeyParts(home));
        } else if (rel.createOnDemand()) {
            // a key for a virtually defined object that's never actually  stored in the db
            // or a key for an object that represents subobjects grouped by some property,
            // generated on the fly
            key = new SyntheticKey(home.getKey(), kstr);
        } else {
            // Not a relation we use can use getNodeByRelation() for
            return null;
        }

        // See if Transactor has already come across this node
        Node node = tx.getVisitedNode(key);

        if ((node != null) && (node.getState() != Node.INVALID)) {
            // we used to refresh the node in the main cache here to avoid the primary key
            // entry being flushed from cache before the secondary one
            // (risking duplicate nodes in cache) but we don't need to since we fetched
            // the node from the threadlocal transactor cache and didn't refresh it in the
            // main cache.
            return node;
        }

        // try to get the node from the shared cache
        node = (Node) cache.get(key);

        // check if we can use the cached node without further checks.
        // we need further checks for subnodes fetched by name if the subnodes were changed.
        if ((node != null) && (node.getState() != Node.INVALID)) {
            // check if node is null node (cached null)
            if (node.isNullNode()) {
                if ((node.created < rel.otherType.getLastDataChange()) ||
                        (node.created < rel.ownType.getLastTypeChange())) {
                    node = null; //  cached null not valid anymore
                }
            } else if (!rel.virtual) {
                // apply different consistency checks for groupby nodes and database nodes:
                // for group nodes, check if they're contained
                if (rel.groupby != null) {
                    if (home.contains(node) < 0) {
                        node = null;
                    }

                // for database nodes, check if constraints are fulfilled
                } else if (!rel.usesPrimaryKey()) {
                    if (!rel.checkConstraints(home, node)) {
                        node = null;
                    }
                }
            }
        }

        if ((node == null) || (node.getState() == Node.INVALID)) {
            // The requested node isn't in the shared cache.
            // Synchronize with key to make sure only one version is fetched
            // from the database.
            node = getNodeByRelation(tx.txn, home, kstr, rel);

            if (node != null) {
                Key primKey = node.getKey();
                boolean keyIsPrimary = primKey.equals(key);

                synchronized (cache) {
                    // check if node is already in cache with primary key
                    Node oldnode = (Node) cache.put(primKey, node);

                    // no need to check for oldnode != node because we fetched a new node from db
                    if ((oldnode != null) && !oldnode.isNullNode() &&
                            (oldnode.getState() != Node.INVALID)) {
                        // reset create time of old node, otherwise Relation.checkConstraints
                        // will reject it under certain circumstances.
                        oldnode.created = oldnode.lastmodified;
                        cache.put(primKey, oldnode);

                        if (!keyIsPrimary) {
                            cache.put(key, oldnode);
                        }

                        node = oldnode;
                    } else if (!keyIsPrimary) {
                        // cache node with secondary key
                        cache.put(key, node);
                    }
                }
                 // synchronized
            } else {
                // node fetched from db is null, cache result using nullNode
                synchronized (cache) {
                    cache.put(key, new Node());

                    // we ignore the case that onother thread has created the node in the meantime
                    return null;
                }
            }
        } else if (node.isNullNode()) {
            // the nullNode caches a null value, i.e. an object that doesn't exist
            return null;
        } else {
            // update primary key in cache to keep it from being flushed, see above
            if (!rel.usesPrimaryKey()) {
                synchronized (cache) {
                    Node oldnode = (Node) cache.put(node.getKey(), node);

                    if ((oldnode != node) && (oldnode != null) &&
                            (oldnode.getState() != Node.INVALID)) {
                        cache.put(node.getKey(), oldnode);
                        cache.put(key, oldnode);
                        node = oldnode;
                    }
                }
            }
        }

        if (node != null) {
            tx.visitCleanNode(key, node);
        }

        // tx.timer.endEvent ("getNode "+kstr);
        return node;
    }

    /**
     * Register a node in the node cache.
     */
    public void registerNode(Node node) {
        cache.put(node.getKey(), node);
    }


    /**
     * Register a node in the node cache using the key argument.
     */
    protected void registerNode(Node node, Key key) {
        cache.put(key, node);
    }

    /**
     * Remove a node from the node cache. If at a later time it is accessed again,
     * it will be refetched from the database.
     */
    public void evictNode(Node node) {
        node.setState(INode.INVALID);
        cache.remove(node.getKey());
    }

    /**
     * Remove a node from the node cache. If at a later time it is accessed again,
     * it will be refetched from the database.
     */
    public void evictNodeByKey(Key key) {
        Node n = (Node) cache.remove(key);

        if (n != null) {
            n.setState(INode.INVALID);

            if (!(key instanceof DbKey)) {
                cache.remove(n.getKey());
            }
        }
    }

    /**
     * Used when a key stops being valid for a node. The cached node itself
     * remains valid, if it is present in the cache by other keys.
     */
    public void evictKey(Key key) {
        cache.remove(key);
    }

    ////////////////////////////////////////////////////////////////////////
    // methods to do the actual db work
    ////////////////////////////////////////////////////////////////////////

    /**
     *  Insert a new node in the embedded database or a relational database table,
     *  depending on its db mapping.
     */
    public void insertNode(IDatabase db, ITransaction txn, Node node)
                    throws IOException, SQLException, ClassNotFoundException {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("insertNode "+node);
        DbMapping dbm = node.getDbMapping();

        if ((dbm == null) || !dbm.isRelational()) {
            db.insertNode(txn, node.getID(), node);
        } else {
            insertRelationalNode(node, dbm, dbm.getConnection());
        }
    }

    /**
     *  Insert a node into a different (relational) database than its default one.
     */
    public void exportNode(Node node, DbSource dbs) 
                    throws IOException, SQLException, ClassNotFoundException {
        if (node == null) {
            throw new IllegalArgumentException("Node can't be null in exportNode");
        }
        
        DbMapping dbm = node.getDbMapping();
        
        if (dbs == null) {
            throw new IllegalArgumentException("DbSource can't be null in exportNode");
        } else if ((dbm == null) || !dbm.isRelational()) {
            throw new IllegalArgumentException("Can't export into non-relational database");
        } else {
            insertRelationalNode(node, dbm, dbs.getConnection());
        }
    }
    
    /**
     *  Insert a node into a different (relational) database than its default one.
     */
    public void exportNode(Node node, DbMapping dbm)
                    throws IOException, SQLException, ClassNotFoundException {
        if (node == null) {
            throw new IllegalArgumentException("Node can't be null in exportNode");
        }

        if (dbm == null) {
            throw new IllegalArgumentException("DbMapping can't be null in exportNode");
        } else if (!dbm.isRelational()) {
            throw new IllegalArgumentException("Can't export into non-relational database");
        } else {
            insertRelationalNode(node, dbm, dbm.getConnection());
        }
    }

    /**
     * Insert a node into a relational database.
     */
    protected void insertRelationalNode(Node node, DbMapping dbm, Connection con)
                throws ClassNotFoundException, SQLException {

        if (con == null) {
            throw new NullPointerException("Error inserting relational node: Connection is null");
        }

        // set connection to write mode
        if (con.isReadOnly()) con.setReadOnly(false);

        String insertString = dbm.getInsert();
        PreparedStatement stmt = con.prepareStatement(insertString);

        // app.logEvent ("inserting relational node: "+node.getID ());
        DbColumn[] columns = dbm.getColumns();

        String nameField = dbm.getNameField();
        String prototypeField = dbm.getPrototypeField();

        long logTimeStart = logSql ? System.currentTimeMillis() : 0;

        try {
            int stmtNumber = 1;

            // first column of insert statement is always the primary key
            stmt.setString(stmtNumber, node.getID());

            Hashtable propMap = node.getPropMap();

            for (int i = 0; i < columns.length; i++) {
                Relation rel = columns[i].getRelation();
                Property p = null;

                if (rel != null && propMap != null && (rel.isPrimitive() || rel.isReference())) {
                    p = (Property) propMap.get(rel.getPropName());
                }

                String name = columns[i].getName();

                if (!((rel != null) && (rel.isPrimitive() || rel.isReference())) &&
                        !name.equalsIgnoreCase(nameField) &&
                        !name.equalsIgnoreCase(prototypeField)) {
                        continue;
                    }

                stmtNumber++;
                if (p!=null) {
                    this.setStatementValues (stmt, stmtNumber, p, columns[i].getType());
                } else if (name.equalsIgnoreCase(nameField)) {
                    stmt.setString(stmtNumber, node.getName());
                } else if (name.equalsIgnoreCase(prototypeField)) {
                    stmt.setString(stmtNumber, node.getPrototype());
                } else {
                    stmt.setNull(stmtNumber, columns[i].getType());
                }
            }

            stmt.executeUpdate();

        } finally {
            if (logSql) {
                long logTimeStop = java.lang.System.currentTimeMillis();
                logSqlStatement("SQL INSERT", dbm.getTableName(),
                                logTimeStart, logTimeStop, insertString);
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (Exception ignore) {}
            }
        }

    }

    /**
     *  Updates a modified node in the embedded db or an external relational database, depending
     * on its database mapping.
     *
     * @return true if the DbMapping of the updated Node is to be marked as updated via
     *              DbMapping.setLastDataChange
     */
    public boolean updateNode(IDatabase db, ITransaction txn, Node node)
                    throws IOException, SQLException, ClassNotFoundException {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("updateNode "+node);
        DbMapping dbm = node.getDbMapping();
        boolean markMappingAsUpdated = false;

        if ((dbm == null) || !dbm.isRelational()) {
            db.updateNode(txn, node.getID(), node);
        } else {
            Hashtable propMap = node.getPropMap();
            Property[] props;

            if (propMap == null) {
                props = new Property[0];
            } else {
                props = new Property[propMap.size()];
                propMap.values().toArray(props);
            }

            // make sure table meta info is loaded by dbmapping
            dbm.getColumns();

            StringBuffer b = dbm.getUpdate();

            // comma flag set after the first dirty column, also tells as
            // if there are dirty columns at all
            boolean comma = false;

            for (int i = 0; i < props.length; i++) {
                // skip clean properties
                if ((props[i] == null) || !props[i].dirty) {
                    // null out clean property so we don't consider it later
                    props[i] = null;

                    continue;
                }

                Relation rel = dbm.propertyToRelation(props[i].getName());

                // skip readonly, virtual and collection relations
                if ((rel == null) || rel.readonly || rel.virtual ||
                        (!rel.isReference() && !rel.isPrimitive())) {
                    // null out property so we don't consider it later
                    props[i] = null;
                    continue;
                }

                if (comma) {
                    b.append(", ");
                } else {
                    comma = true;
                }

                b.append(rel.getDbField());
                b.append(" = ?");
            }

            // if no columns were updated, return false
            if (!comma) {
                return false;
            }

            b.append(" WHERE ");
            b.append(dbm.getIDField());
            b.append(" = ");

            if (dbm.needsQuotes(dbm.getIDField())) {
                b.append("'");
                b.append(escape(node.getID()));
                b.append("'");
            } else {
                b.append(node.getID());
            }

            Connection con = dbm.getConnection();
            // set connection to write mode
            if (con.isReadOnly()) con.setReadOnly(false);
            PreparedStatement stmt = con.prepareStatement(b.toString());

            int stmtNumber = 0;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;

            try {
                for (int i = 0; i < props.length; i++) {
                    Property p = props[i];

                    if (p == null) {
                        continue;
                    }

                    Relation rel = dbm.propertyToRelation(p.getName());

                    stmtNumber++;
                    this.setStatementValues (stmt, stmtNumber, p, rel.getColumnType());

                    p.dirty = false;

                    if (!rel.isPrivate()) {
                        markMappingAsUpdated = true;
                    }
                }

                stmt.executeUpdate();

            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL UPDATE", dbm.getTableName(),
                                    logTimeStart, logTimeStop, b.toString());
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        // update may cause changes in the node's parent subnode array
        // TODO: is this really needed anymore?
        if (markMappingAsUpdated && node.isAnonymous()) {
            Node parent = node.getCachedParent();

            if (parent != null) {
                parent.setLastSubnodeChange(System.currentTimeMillis());
            }
        }

        return markMappingAsUpdated;
    }

    /**
     *  Performs the actual deletion of a node from either the embedded or an external
     *  SQL database.
     */
    public void deleteNode(IDatabase db, ITransaction txn, Node node)
                    throws Exception {
        DbMapping dbm = node.getDbMapping();

        if ((dbm == null) || !dbm.isRelational()) {
            db.deleteNode(txn, node.getID());
        } else {
            Statement st = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;
            String str = new StringBuffer("DELETE FROM ").append(dbm.getTableName())
                                                         .append(" WHERE ")
                                                         .append(dbm.getIDField())
                                                         .append(" = ")
                                                         .append(node.getID())
                                                         .toString();

            try {
                Connection con = dbm.getConnection();
                // set connection to write mode
                if (con.isReadOnly()) con.setReadOnly(false);

                st = con.createStatement();

                st.executeUpdate(str);

            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL DELETE", dbm.getTableName(),
                                    logTimeStart, logTimeStop, str);
                }
                if (st != null) {
                    try {
                        st.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }

        // node may still be cached via non-primary keys. mark as invalid
        node.setState(Node.INVALID);
    }


    /**
     * Generate a new ID for a given type, delegating to our IDGenerator if set.
     */
    public String generateID(DbMapping map) throws Exception {
        if (idgen != null) {
            // use our custom IDGenerator
            return idgen.generateID(map);
        } else {
            return doGenerateID(map);
        }
    }

    /**
     * Actually generates an ID, using a method matching the given DbMapping.
     */
    public String doGenerateID(DbMapping map) throws Exception {
        if ((map == null) || !map.isRelational()) {
            // use embedded db id generator
            return generateEmbeddedID(map);
        }
        String idMethod = map.getIDgen();
        if (idMethod == null || "[max]".equalsIgnoreCase(idMethod) || map.isMySQL()) {
            // use select max as id generator
            return generateMaxID(map);
        } else if ("[hop]".equalsIgnoreCase(idMethod)) {
            // use embedded db id generator
            return generateEmbeddedID(map);
        } else {
            // use db sequence as id generator
            return generateSequenceID(map);
        }
    }

    /**
     * Gererates an ID for use with the embedded database.
     */
    synchronized String generateEmbeddedID(DbMapping map) throws Exception {
        return db.nextID();
    }

    /**
     * Generates an ID for the table by finding out the maximum current value
     */
    synchronized String generateMaxID(DbMapping map)
                                      throws Exception {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("generateID "+map);
        String retval = null;
        Statement stmt = null;
        long logTimeStart = logSql ? System.currentTimeMillis() : 0;
        String q = new StringBuffer("SELECT MAX(").append(map.getIDField())
                                                  .append(") FROM ")
                                                  .append(map.getTableName())
                                                  .toString();

        try {
            Connection con = map.getConnection();
            // set connection to read-only mode
            if (!con.isReadOnly()) con.setReadOnly(true);

            stmt = con.createStatement();

            ResultSet rs = stmt.executeQuery(q);

            // check for empty table
            if (!rs.next()) {
                long currMax = map.getNewID(0);

                retval = Long.toString(currMax);
            } else {
                long currMax = rs.getLong(1);

                currMax = map.getNewID(currMax);
                retval = Long.toString(currMax);
            }
        } finally {
            if (logSql) {
                long logTimeStop = System.currentTimeMillis();
                logSqlStatement("SQL SELECT_MAX", map.getTableName(),
                                logTimeStart, logTimeStop, q);
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (Exception ignore) {
                }
            }
        }

        return retval;
    }

    String generateSequenceID(DbMapping map) throws Exception {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("generateID "+map);
        Statement stmt = null;
        String retval = null;
        long logTimeStart = logSql ? System.currentTimeMillis() : 0;
        String q;
        if (map.isOracle()) {
            q = new StringBuffer("SELECT ").append(map.getIDgen())
                    .append(".nextval FROM dual").toString();
        } else if (map.isPostgreSQL()) {
            q = new StringBuffer("SELECT nextval('")
                    .append(map.getIDgen()).append("')").toString();
        } else {
            throw new RuntimeException("Unable to generate sequence: unknown DB");
        }

        try {
            Connection con = map.getConnection();
            // TODO is it necessary to set connection to write mode here?
            if (con.isReadOnly()) con.setReadOnly(false);

            stmt = con.createStatement();

            ResultSet rs = stmt.executeQuery(q);

            if (!rs.next()) {
                throw new SQLException("Error creating ID from Sequence: empty recordset");
            }

            retval = rs.getString(1);
        } finally {
            if (logSql) {
                long logTimeStop = System.currentTimeMillis();
                logSqlStatement("SQL SELECT_NEXTVAL", map.getTableName(),
                                logTimeStart, logTimeStop, q);
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (Exception ignore) {
                }
            }
        }

        return retval;
    }

    /**
     *  Loades subnodes via subnode relation. Only the ID index is loaded, the nodes are
     *  loaded later on demand.
     */
    public SubnodeList getNodeIDs(Node home, Relation rel) throws Exception {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("getNodeIDs "+home);

        if ((rel == null) || (rel.otherType == null) || !rel.otherType.isRelational()) {
            // this should never be called for embedded nodes
            throw new RuntimeException("NodeMgr.getNodeIDs called for non-relational node " +
                                       home);
        } else {
            SubnodeList retval = home.createSubnodeList();

            // if we do a groupby query (creating an intermediate layer of groupby nodes),
            // retrieve the value of that field instead of the primary key
            String idfield = (rel.groupby == null) ? rel.otherType.getIDField()
                                                   : rel.groupby;
            Connection con = rel.otherType.getConnection();
            // set connection to read-only mode
            if (!con.isReadOnly()) con.setReadOnly(true);

            String table = rel.otherType.getTableName();

            Statement stmt = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;
            String query = null;

            try {
                StringBuffer b = new StringBuffer("SELECT ");

                if (rel.queryHints != null) {
                    b.append(rel.queryHints).append(" ");
                }

                if (idfield.indexOf('(') == -1 && idfield.indexOf('.') == -1) {
                    b.append(table).append('.');
                }
                b.append(idfield).append(" FROM ").append(table);

                rel.appendAdditionalTables(b);

                if (home.getSubnodeRelation() != null) {
                    // subnode relation was explicitly set
                    query = b.append(" ").append(home.getSubnodeRelation()).toString();
                } else {
                    // let relation object build the query
                    query = b.append(rel.buildQuery(home,
                                                home.getNonVirtualParent(),
                                                null,
                                                " WHERE ",
                                                true)).toString();
                }

                stmt = con.createStatement();

                if (rel.maxSize > 0) {
                    stmt.setMaxRows(rel.maxSize);
                }

                ResultSet result = stmt.executeQuery(query);

                // problem: how do we derive a SyntheticKey from a not-yet-persistent Node?
                Key k = (rel.groupby != null) ? home.getKey() : null;

                while (result.next()) {
                    String kstr = result.getString(1);

                    // jump over null values - this can happen especially when the selected
                    // column is a group-by column.
                    if (kstr == null) {
                        continue;
                    }

                    // make the proper key for the object, either a generic DB key or a groupby key
                    Key key = (rel.groupby == null)
                              ? (Key) new DbKey(rel.otherType, kstr)
                              : (Key) new SyntheticKey(k, kstr);

                    retval.addSorted(new NodeHandle(key));

                    // if these are groupby nodes, evict nullNode keys
                    if (rel.groupby != null) {
                        Node n = (Node) cache.get(key);

                        if ((n != null) && n.isNullNode()) {
                            evictKey(key);
                        }
                    }
                }
            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_IDS", table,
                                    logTimeStart, logTimeStop, query);
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }

            return retval;
        }
    }

    /**
     *  Loades subnodes via subnode relation. This is similar to getNodeIDs, but it
     *  actually loades all nodes in one go, which is better for small node collections.
     *  This method is used when xxx.loadmode=aggressive is specified.
     */
    public SubnodeList getNodes(Node home, Relation rel) throws Exception {
        // This does not apply for groupby nodes - use getNodeIDs instead
        if (rel.groupby != null) {
            return getNodeIDs(home, rel);
        }

        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("getNodes "+home);
        if ((rel == null) || (rel.otherType == null) || !rel.otherType.isRelational()) {
            // this should never be called for embedded nodes
            throw new RuntimeException("NodeMgr.getNodes called for non-relational node " +
                                       home);
        } else {
            SubnodeList retval = home.createSubnodeList();
            DbMapping dbm = rel.otherType;

            Connection con = dbm.getConnection();
            // set connection to read-only mode
            if (!con.isReadOnly()) con.setReadOnly(true);

            Statement stmt = con.createStatement();
            DbColumn[] columns = dbm.getColumns();
            Relation[] joins = dbm.getJoins();
            String query = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;

            try {
                StringBuffer b = dbm.getSelect(rel);

                if (home.getSubnodeRelation() != null) {
                    b.append(home.getSubnodeRelation());
                } else {
                    // let relation object build the query
                    b.append(rel.buildQuery(home,
                                            home.getNonVirtualParent(),
                                            null,
                                            " WHERE ",
                                            true));
                }

                query = b.toString();

                if (rel.maxSize > 0) {
                    stmt.setMaxRows(rel.maxSize);
                }

                ResultSet rs = stmt.executeQuery(query);

                while (rs.next()) {
                    // create new Nodes.
                    Node node = createNode(rel.otherType, rs, columns, 0);
                    if (node == null) {
                        continue;
                    }
                    Key primKey = node.getKey();

                    retval.addSorted(new NodeHandle(primKey));

                    // do we need to synchronize on primKey here?
                    synchronized (cache) {
                        Node oldnode = (Node) cache.put(primKey, node);

                        if ((oldnode != null) && (oldnode.getState() != INode.INVALID)) {
                            cache.put(primKey, oldnode);
                        }
                    }

                    fetchJoinedNodes(rs, joins, columns.length);
                }

            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_ALL", dbm.getTableName(),
                                    logTimeStart, logTimeStop, query);
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }

            return retval;
        }
    }
    
    /**
     * Update a UpdateableSubnodeList retrieving all values having
     * higher Values according to the updateCriteria's set for this Collection's Relation
     * The returned Map-Object has two Properties:
     * addedNodes = an Integer representing the number of Nodes added to this collection
     * newNodes = an Integer representing the number of Records returned by the Select-Statement
     * These two values may be different if a max-size is defined for this Collection and a new
     * node would be outside of this Border because of the ordering of this collection.
     * @param home the home of this subnode-list
     * @param rel the relation the home-node has to the nodes contained inside the subnodelist
     * @return A map having two properties of type String (newNodes (number of nodes retreived by the select-statment), addedNodes (nodes added to the collection))
     * @throws Exception
     */
    public int updateSubnodeList(Node home, Relation rel) throws Exception {
        if ((rel == null) || (rel.otherType == null) || !rel.otherType.isRelational()) {
            // this should never be called for embedded nodes
            throw new RuntimeException("NodeMgr.updateSubnodeList called for non-relational node " +
                                       home);
        } else {
            List list = home.getSubnodeList();
            if (list == null)
                list = home.createSubnodeList();
            
            if (!(list instanceof UpdateableSubnodeList))
                throw new RuntimeException ("unable to update SubnodeList not marked as updateable (" + rel.propName + ")");
            
            UpdateableSubnodeList sublist = (UpdateableSubnodeList) list;
            
            // FIXME: grouped subnodes aren't supported yet
            if (rel.groupby != null)
                throw new RuntimeException ("update not yet supported on grouped collections");

            String idfield = rel.otherType.getIDField();
            Connection con = rel.otherType.getConnection();
            String table = rel.otherType.getTableName();

            Statement stmt = null;

            try {
                String q = null;

                StringBuffer b = new StringBuffer();
                if (rel.loadAggressively()) {
                    b.append (rel.otherType.getSelect(rel));
                } else {
                    b.append ("SELECT ");
                    if (rel.queryHints != null) {
                        b.append(rel.queryHints).append(" ");
                    }
                    b.append(table).append('.')
                                   .append(idfield).append(" FROM ")
                                   .append(table);

                    rel.appendAdditionalTables(b);
                }
                String updateCriteria = sublist.getUpdateCriteria();
                if (home.getSubnodeRelation() != null) {
                    if (updateCriteria != null) {
                        b.append (" WHERE ");
                        b.append (sublist.getUpdateCriteria());
                        b.append (" AND ");
                        b.append (home.getSubnodeRelation());
                    } else {
                        b.append (" WHERE ");
                        b.append (home.getSubnodeRelation());
                    }
                } else {
                    if (updateCriteria != null) {
                        b.append (" WHERE ");
                        b.append (updateCriteria);
                        b.append (rel.buildQuery(home,
                                home.getNonVirtualParent(),
                                null,
                                " AND ",
                                true));
                    } else {
                        b.append (rel.buildQuery(home,
                                home.getNonVirtualParent(),
                                null,
                                " WHERE ",
                                true));
                    }
                    q = b.toString();
                }

                long logTimeStart = logSql ? System.currentTimeMillis() : 0;

                stmt = con.createStatement();

                if (rel.maxSize > 0) {
                    stmt.setMaxRows(rel.maxSize);
                }

                ResultSet result = stmt.executeQuery(q);

                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_UPDATE_SUBNODE_LIST", table,
                                    logTimeStart, logTimeStop, q);
                }

                // problem: how do we derive a SyntheticKey from a not-yet-persistent Node?
                // Key k = (rel.groupby != null) ? home.getKey() : null;
                // int cntr = 0;
                
                DbColumn[] columns = rel.loadAggressively() ? rel.otherType.getColumns() : null;
                List newNodes = new ArrayList(rel.maxSize);
                while (result.next()) {
                    String kstr = result.getString(1);

                    // jump over null values - this can happen especially when the selected
                    // column is a group-by column.
                    if (kstr == null) {
                        continue;
                    }

                    // make the proper key for the object, either a generic DB key or a groupby key
                    Key key;
                    if (rel.loadAggressively()) {
                        Node node = createNode(rel.otherType, result, columns, 0);
                        if (node == null) {
                            continue;
                        }
                        key = node.getKey();
                        synchronized (cache) {
                            Node oldnode = (Node) cache.put(key, node);
                            if ((oldnode != null) && (oldnode.getState() != INode.INVALID)) {
                                cache.put(key, oldnode);
                            }
                        }
                    } else {
                        key = new DbKey(rel.otherType, kstr);
                    }
                    newNodes.add(new NodeHandle(key));

                    // if these are groupby nodes, evict nullNode keys
                    if (rel.groupby != null) {
                        Node n = (Node) cache.get(key);

                        if ((n != null) && n.isNullNode()) {
                            evictKey(key);
                        }
                    }
                }
                // System.err.println("GOT NEW NODES: " + newNodes);
                if (!newNodes.isEmpty())
                    sublist.addAll(newNodes);
                return newNodes.size();
            } finally {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     *
     */
    public void prefetchNodes(Node home, Relation rel, Key[] keys)
                       throws Exception {
        DbMapping dbm = rel.otherType;

        if ((dbm == null) || !dbm.isRelational()) {
            // this does nothing for objects in the embedded database
            return;
        } else {
            int missing = cache.containsKeys(keys);

            if (missing > 0) {
                Connection con = dbm.getConnection();
                // set connection to read-only mode
                if (!con.isReadOnly()) con.setReadOnly(true);

                Statement stmt = con.createStatement();
                DbColumn[] columns = dbm.getColumns();
                Relation[] joins = dbm.getJoins();
                String query = null;
                long logTimeStart = logSql ? System.currentTimeMillis() : 0;

                try {
                    StringBuffer b = dbm.getSelect(null);

                    String idfield = (rel.groupby != null) ? rel.groupby : dbm.getIDField();
                    boolean needsQuotes = dbm.needsQuotes(idfield);

                    b.append(" WHERE ");
                    b.append(dbm.getTableName());
                    b.append(".");
                    b.append(idfield);
                    b.append(" IN (");

                    boolean first = true;

                    for (int i = 0; i < keys.length; i++) {
                        if (keys[i] != null) {
                            if (!first) {
                                b.append(',');
                            } else {
                                first = false;
                            }

                            if (needsQuotes) {
                                b.append("'");
                                b.append(escape(keys[i].getID()));
                                b.append("'");
                            } else {
                                b.append(keys[i].getID());
                            }
                        }
                    }

                    b.append(") ");

                    dbm.addJoinConstraints(b, " AND ");

                    if (rel.groupby != null) {
                        rel.renderConstraints(b, home, home.getNonVirtualParent(), " AND ");

                        if (rel.order != null) {
                            b.append(" ORDER BY ");
                            b.append(rel.order);
                        }
                    }

                    query = b.toString();

                    ResultSet rs = stmt.executeQuery(query);

                    String groupbyProp = null;
                    HashMap groupbySubnodes = null;

                    if (rel.groupby != null) {
                        groupbyProp = dbm.columnNameToProperty(rel.groupby);
                        groupbySubnodes = new HashMap();
                    }

                    String accessProp = null;

                    if ((rel.accessName != null) && !rel.usesPrimaryKey()) {
                        accessProp = dbm.columnNameToProperty(rel.accessName);
                    }

                    while (rs.next()) {
                        // create new Nodes.
                        Node node = createNode(dbm, rs, columns, 0);
                        if (node == null) {
                            continue;
                        }
                        Key primKey = node.getKey();

                        // for grouped nodes, collect subnode lists for the intermediary
                        // group nodes.
                        String groupName = null;

                        if (groupbyProp != null) {
                            groupName = node.getString(groupbyProp);

                            SubnodeList sn = (SubnodeList) groupbySubnodes.get(groupName);

                            if (sn == null) {
                                sn = new SubnodeList(safe, rel);
                                groupbySubnodes.put(groupName, sn);
                            }

                            sn.addSorted(new NodeHandle(primKey));
                        }

                        // if relation doesn't use primary key as accessName, get accessName value
                        String accessName = null;

                        if (accessProp != null) {
                            accessName = node.getString(accessProp);
                        }

                        // register new nodes with the cache. If an up-to-date copy
                        // existed in the cache, use that.
                        synchronized (cache) {
                            Node oldnode = (Node) cache.put(primKey, node);

                            if ((oldnode != null) &&
                                    (oldnode.getState() != INode.INVALID)) {
                                // found an ok version in the cache, use it.
                                cache.put(primKey, oldnode);
                            } else if (accessName != null) {
                                // put the node into cache with its secondary key
                                if (groupName != null) {
                                    cache.put(new SyntheticKey(new SyntheticKey(home.getKey(),
                                                                                groupName),
                                                               accessName), node);
                                } else {
                                    cache.put(new SyntheticKey(home.getKey(), accessName),
                                              node);
                                }
                            }
                        }

                        fetchJoinedNodes(rs, joins, columns.length);
                    }

                    // If these are grouped nodes, build the intermediary group nodes
                    // with the subnod lists we created
                    if (groupbyProp != null) {
                        for (Iterator i = groupbySubnodes.keySet().iterator();
                                 i.hasNext();) {
                            String groupname = (String) i.next();

                            if (groupname == null) {
                                continue;
                            }

                            Node groupnode = home.getGroupbySubnode(groupname, true);

                            groupnode.setSubnodes((SubnodeList) groupbySubnodes.get(groupname));
                            groupnode.lastSubnodeFetch = System.currentTimeMillis();
                        }
                    }
                } catch (Exception x) {
                    System.err.println ("Error in prefetchNodes(): "+x);
                } finally {
                    if (logSql) {
                        long logTimeStop = System.currentTimeMillis();
                        logSqlStatement("SQL SELECT_PREFETCH", dbm.getTableName(),
                                        logTimeStart, logTimeStop, query);
                    }
                    if (stmt != null) {
                        try {
                            stmt.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Count the nodes contained in the child collection of the home node
     * which is defined by Relation rel.
     */
    public int countNodes(Node home, Relation rel) throws Exception {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("countNodes "+home);
        if ((rel == null) || (rel.otherType == null) || !rel.otherType.isRelational()) {
            // this should never be called for embedded nodes
            throw new RuntimeException("NodeMgr.countNodes called for non-relational node " +
                                       home);
        } else {
            int retval = 0;
            Connection con = rel.otherType.getConnection();
            // set connection to read-only mode
            if (!con.isReadOnly()) con.setReadOnly(true);

            String table = rel.otherType.getTableName();
            Statement stmt = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;
            String query = null;

            try {
                StringBuffer tables = new StringBuffer(table);

                rel.appendAdditionalTables(tables);

                // NOTE: we explicitly convert tables StringBuffer to a String
                // before appending to be compatible with JDK 1.3
                StringBuffer b = new StringBuffer("SELECT count(*) FROM ")
                        .append(tables.toString());

                if (home.getSubnodeRelation() != null) {
                    // use the manually set subnoderelation of the home node
                    query = b.append(" ").append(home.getSubnodeRelation()).toString();
                } else {
                    // let relation object build the query
                    query = b.append(rel.buildQuery(home,
                                                home.getNonVirtualParent(),
                                                null,
                                                " WHERE ",
                                                false)).toString();
                }

                stmt = con.createStatement();


                ResultSet rs = stmt.executeQuery(query);


                if (!rs.next()) {
                    retval = 0;
                } else {
                    retval = rs.getInt(1);
                }
            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_COUNT", table,
                                    logTimeStart, logTimeStop, query);
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }

            return (rel.maxSize > 0) ? Math.min(rel.maxSize, retval) : retval;
        }
    }

    /**
     *  Similar to getNodeIDs, but returns a Vector that return's the nodes property names instead of IDs
     */
    public Vector getPropertyNames(Node home, Relation rel)
                            throws Exception {
        // Transactor tx = (Transactor) Thread.currentThread ();
        // tx.timer.beginEvent ("getNodeIDs "+home);
        if ((rel == null) || (rel.otherType == null) || !rel.otherType.isRelational()) {
            // this should never be called for embedded nodes
            throw new RuntimeException("NodeMgr.getPropertyNames called for non-relational node " +
                                       home);
        } else {
            Vector retval = new Vector();

            // if we do a groupby query (creating an intermediate layer of groupby nodes),
            // retrieve the value of that field instead of the primary key
            String namefield = (rel.groupby == null) ? rel.accessName : rel.groupby;
            Connection con = rel.otherType.getConnection();
            // set connection to read-only mode
            if (!con.isReadOnly()) con.setReadOnly(true);

            String table = rel.otherType.getTableName();
            StringBuffer tables = new StringBuffer(table);
            rel.appendAdditionalTables(tables);

            Statement stmt = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;
            String query = null;

            try {
                // NOTE: we explicitly convert tables StringBuffer to a String
                // before appending to be compatible with JDK 1.3
                StringBuffer b = new StringBuffer("SELECT ").append(namefield)
                                                            .append(" FROM ")
                                                            .append(tables.toString());

                if (home.getSubnodeRelation() != null) {
                    b.append(" ").append(home.getSubnodeRelation());
                } else {
                    // let relation object build the query
                    b.append(rel.buildQuery(home,
                                            home.getNonVirtualParent(),
                                            null,
                                            " WHERE ",
                                            true));
                }

                stmt = con.createStatement();

                query = b.toString();

                ResultSet rs = stmt.executeQuery(query);

                while (rs.next()) {
                    String n = rs.getString(1);

                    if (n != null) {
                        retval.addElement(n);
                    }
                }
            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_ACCESSNAMES", table,
                                    logTimeStart, logTimeStop, query);
                }

                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                    }
                }
            }

            return retval;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////
    // private getNode methods
    ///////////////////////////////////////////////////////////////////////////////////////
    private Node getNodeByKey(ITransaction txn, DbKey key)
                       throws Exception {
        // Note: Key must be a DbKey, otherwise will not work for relational objects
        Node node = null;
        DbMapping dbm = app.getDbMapping(key.getStorageName());
        String kstr = key.getID();

        if ((dbm == null) || !dbm.isRelational()) {
            node = (Node) db.getNode(txn, kstr);
            node.nmgr = safe;

            if ((node != null) && (dbm != null)) {
                node.setDbMapping(dbm);
            }
        } else {
            String idfield = dbm.getIDField();

            Statement stmt = null;
            String query = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;

            try {
                Connection con = dbm.getConnection();
                // set connection to read-only mode
                if (!con.isReadOnly()) con.setReadOnly(true);

                stmt = con.createStatement();

                DbColumn[] columns = dbm.getColumns();
                Relation[] joins = dbm.getJoins();
                StringBuffer b = dbm.getSelect(null).append("WHERE ")
                                                .append(dbm.getTableName())
                                                .append(".")
                                                .append(idfield)
                                                .append(" = ");

                if (dbm.needsQuotes(idfield)) {
                    b.append("'");
                    b.append(escape(kstr));
                    b.append("'");
                } else {
                    b.append(kstr);
                }

                dbm.addJoinConstraints(b, " AND ");

                query = b.toString();

                ResultSet rs = stmt.executeQuery(query);

                if (!rs.next()) {
                    return null;
                }

                node = createNode(dbm, rs, columns, 0);

                fetchJoinedNodes(rs, joins, columns.length);

                if (rs.next()) {
                    app.logError("Warning: More than one value returned for query " + query);
                }
            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_BYKEY", dbm.getTableName(),
                                    logTimeStart, logTimeStop, query);
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }
        }

        return node;
    }

    private Node getNodeByRelation(ITransaction txn, Node home, String kstr, Relation rel)
                            throws Exception {
        Node node = null;

        if ((rel != null) && rel.virtual) {
            if (rel.needsPersistence()) {
                node = (Node) home.createNode(kstr);
            } else {
                node = new Node(home, kstr, safe, rel.prototype);
            }

            // set prototype and dbmapping on the newly created virtual/collection node
            node.setPrototype(rel.prototype);
            node.setDbMapping(rel.getVirtualMapping());
        } else if ((rel != null) && (rel.groupby != null)) {
            node = home.getGroupbySubnode(kstr, false);

            if ((node == null) &&
                    ((rel.otherType == null) || !rel.otherType.isRelational())) {
                node = (Node) db.getNode(txn, kstr);
                node.nmgr = safe;
            }

            return node;
        } else if ((rel == null) || (rel.otherType == null) ||
                       !rel.otherType.isRelational()) {
            node = (Node) db.getNode(txn, kstr);
            node.nmgr = safe;
            node.setDbMapping(rel.otherType);

            return node;
        } else {
            DbMapping dbm = rel.otherType;
            Statement stmt = null;
            String query = null;
            long logTimeStart = logSql ? System.currentTimeMillis() : 0;

            try {
                Connection con = dbm.getConnection();
                // set connection to read-only mode
                if (!con.isReadOnly()) con.setReadOnly(true);
                DbColumn[] columns = dbm.getColumns();
                Relation[] joins = dbm.getJoins();
                StringBuffer b = dbm.getSelect(rel);

                if (home.getSubnodeRelation() != null && !rel.isComplexReference()) {
                    // combine our key with the constraints in the manually set subnode relation
                    b.append(" WHERE ");
                    if (rel.accessName.indexOf('(') == -1 && rel.accessName.indexOf('.') == -1) {
                        b.append(dbm.getTableName());
                        b.append(".");
                    }
                    b.append(rel.accessName);
                    b.append(" = '");
                    b.append(escape(kstr));
                    b.append("'");
                    // add join contraints in case this is an old oracle style join
                    dbm.addJoinConstraints(b, " AND ");
                    // add potential constraints from manually set subnodeRelation
                    String subrel = home.getSubnodeRelation().trim();
                    if (subrel.length() > 5) {
                        b.append(" AND (");
                        b.append(subrel.substring(5).trim());
                        b.append(")");
                    }
                } else {
                    b.append(rel.buildQuery(home,
                                            home.getNonVirtualParent(),
                                            kstr,
                                            " WHERE ",
                                            false));
                }

                stmt = con.createStatement();

                query = b.toString();

                ResultSet rs = stmt.executeQuery(query);

                if (!rs.next()) {
                    return null;
                }

                node = createNode(rel.otherType, rs, columns, 0);

                fetchJoinedNodes(rs, joins, columns.length);

                if (rs.next()) {
                    app.logError("Warning: More than one value returned for query " + query);
                }

                // Check if node is already cached with primary Key.
                if (!rel.usesPrimaryKey()) {
                    Key pk = node.getKey();
                    Node existing = (Node) cache.get(pk);

                    if ((existing != null) && (existing.getState() != Node.INVALID)) {
                        node = existing;
                    }
                }

            } finally {
                if (logSql) {
                    long logTimeStop = System.currentTimeMillis();
                    logSqlStatement("SQL SELECT_BYRELATION", dbm.getTableName(),
                                    logTimeStart, logTimeStop, query);
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
            }
        }

        return node;
    }

    /**
     *  Create a new Node from a ResultSet.
     */
    public Node createNode(DbMapping dbm, ResultSet rs, DbColumn[] columns, int offset)
                throws SQLException, IOException, ClassNotFoundException {
        HashMap propBuffer = new HashMap();
        String id = null;
        String name = null;
        String protoName = dbm.getTypeName();
        DbMapping dbmap = dbm;

        Node node = new Node();

        for (int i = 0; i < columns.length; i++) {

            // set prototype?
            if (columns[i].isPrototypeField()) {
                protoName = rs.getString(i+1+offset);

                if (protoName != null) {
                    dbmap = getDbMapping(protoName);

                    if (dbmap == null) {
                        // invalid prototype name!
                        app.logError("Warning: Invalid prototype name: " + protoName +
                                       " - using default");
                        dbmap = dbm;
                        protoName = dbmap.getTypeName();
                    }
                }
            }

            // set id?
            if (columns[i].isIdField()) {
                id = rs.getString(i+1+offset);
                // if id == null, the object doesn't actually exist - return null
                if (id == null) {
                    return null;
                }
            }

            // set name?
            if (columns[i].isNameField()) {
                name = rs.getString(i+1+offset);
            }

            Property newprop = new Property(node);

            switch (columns[i].getType()) {
                case Types.BIT:
                    newprop.setBooleanValue(rs.getBoolean(i+1+offset));

                    break;

                case Types.TINYINT:
                case Types.BIGINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                    newprop.setIntegerValue(rs.getLong(i+1+offset));

                    break;

                case Types.REAL:
                case Types.FLOAT:
                case Types.DOUBLE:
                    newprop.setFloatValue(rs.getDouble(i+1+offset));

                    break;

                case Types.DECIMAL:
                case Types.NUMERIC:

                    BigDecimal num = rs.getBigDecimal(i+1+offset);

                    if (num == null) {
                        break;
                    }

                    if (num.scale() > 0) {
                        newprop.setFloatValue(num.doubleValue());
                    } else {
                        newprop.setIntegerValue(num.longValue());
                    }

                    break;

                case Types.VARBINARY:
                case Types.BINARY:
                    newprop.setStringValue(rs.getString(i+1+offset));

                    break;

                case Types.LONGVARBINARY:
                case Types.LONGVARCHAR:
                    try {
                        newprop.setStringValue(rs.getString(i+1+offset));
                    } catch (SQLException x) {
                        Reader in = rs.getCharacterStream(i+1+offset);
                        char[] buffer = new char[2048];
                        int read = 0;
                        int r;

                        while ((r = in.read(buffer, read, buffer.length - read)) > -1) {
                            read += r;

                            if (read == buffer.length) {
                                // grow input buffer
                                char[] newBuffer = new char[buffer.length * 2];

                                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                                buffer = newBuffer;
                            }
                        }

                        newprop.setStringValue(new String(buffer, 0, read));
                    }

                    break;

                case Types.CHAR:
                case Types.VARCHAR:
                case Types.OTHER:
                    newprop.setStringValue(rs.getString(i+1+offset));

                    break;

                case Types.DATE:
                case Types.TIME:
                case Types.TIMESTAMP:
                    newprop.setDateValue(rs.getTimestamp(i+1+offset));

                    break;

                case Types.NULL:
                    newprop.setStringValue(null);

                    break;

                case Types.CLOB:
                    Clob cl = rs.getClob(i+1+offset);
                    if (cl==null) {
                        newprop.setStringValue(null);
                        break;
                    }
                    char[] c = new char[(int) cl.length()];
                    Reader isr = cl.getCharacterStream();
                    isr.read(c);
                    newprop.setStringValue(String.copyValueOf(c));
                    break;

                default:
                    newprop.setStringValue(rs.getString(i+1+offset));

                    break;
            }

            if (rs.wasNull()) {
                newprop.setStringValue(null);
            }

            propBuffer.put(columns[i].getName(), newprop);

            // mark property as clean, since it's fresh from the db
            newprop.dirty = false;
        }

        if (id == null) {
            return null;
        }

        Hashtable propMap = new Hashtable();
        DbColumn[] columns2 = dbmap.getColumns();
        for (int i=0; i<columns2.length; i++) {
            Relation rel = columns2[i].getRelation();
            if (rel != null && (rel.reftype == Relation.PRIMITIVE ||
                                rel.reftype == Relation.REFERENCE)) {
                Property prop = (Property) propBuffer.get(columns2[i].getName());

                if (prop == null) {
                    continue;
                }

                prop.setName(rel.propName);

                // if the property is a pointer to another node, change the property type to NODE
                if ((rel.reftype == Relation.REFERENCE) && rel.usesPrimaryKey()) {
                    // FIXME: References to anything other than the primary key are not supported
                    prop.convertToNodeReference(rel.otherType);
                }
                propMap.put(rel.propName.toLowerCase(), prop);
            }
        }

        node.init(dbmap, id, name, protoName, propMap, safe);
        node.invokeOnInit();
        return node;
    }

    /**
     *  Fetch nodes that are fetched additionally to another node via join.
     */
    private void fetchJoinedNodes(ResultSet rs, Relation[] joins, int offset)
            throws ClassNotFoundException, SQLException, IOException {
        int resultSetOffset = offset;
        // create joined objects
        for (int i = 0; i < joins.length; i++) {
            DbMapping jdbm = joins[i].otherType;
            Node node = createNode(jdbm, rs, jdbm.getColumns(), resultSetOffset);
            if (node != null) {
                Key primKey = node.getKey();
                // register new nodes with the cache. If an up-to-date copy
                // existed in the cache, use that.
                synchronized (cache) {
                    Node oldnode = (Node) cache.put(primKey, node);

                    if ((oldnode != null) &&
                            (oldnode.getState() != INode.INVALID)) {
                        // found an ok version in the cache, use it.
                        cache.put(primKey, oldnode);
                    }
                }
            }
            resultSetOffset += jdbm.getColumns().length;
        }
    }


    /**
     * Get a DbMapping for a given prototype name. This is just a proxy
     * method to the app's getDbMapping() method.
     */
    public DbMapping getDbMapping(String protoname) {
        return app.getDbMapping(protoname);
    }

    // a utility method to escape single quotes
    private String escape(String str) {
        if (str == null) {
            return null;
        }

        if (str.indexOf("'") < 0) {
            return str;
        }

        int l = str.length();
        StringBuffer sbuf = new StringBuffer(l + 10);

        for (int i = 0; i < l; i++) {
            char c = str.charAt(i);

            if (c == '\'') {
                sbuf.append('\'');
            }

            sbuf.append(c);
        }

        return sbuf.toString();
    }

    /**
     *  Get an array of the the keys currently held in the object cache
     */
    public Object[] getCacheEntries() {
        return cache.getCachedObjects();
    }

    /**
     * Get the number of elements in the object cache
     */
    public int countCacheEntries() {
        return cache.size();
    }

    /**
     * Clear the object cache, causing all objects to be recreated.
     */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** 
     * Add a listener that is notified each time a transaction commits 
     * that adds, modifies or deletes any Nodes.
     */
    public void addNodeChangeListener(NodeChangeListener listener) {
        listeners.add(listener);
    }
    
    /** 
     * Remove a previously added NodeChangeListener. 
     */
    public void removeNodeChangeListener(NodeChangeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Let transactors know if they should collect and fire NodeChangeListener
     * events
     */
    protected boolean hasNodeChangeListeners() {
        return listeners.size() > 0;
    }
    
    /**
     * Called by transactors after committing.
     */
    protected void fireNodeChangeEvent(List inserted, List updated, List deleted, List parents) {
        int l = listeners.size();

        for (int i=0; i<l; i++) {
            try {
                ((NodeChangeListener) listeners.get(i)).nodesChanged(inserted, updated, deleted, parents);
            } catch (Error e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    

    /**
     *  Receive notification from a remote app that objects in its cache have been
     * modified.
     */
    public void replicateCache(Vector add, Vector delete) {
        if (logReplication) {
            app.logEvent("Received cache replication event: " + add.size() + " added, " +
                         delete.size() + " deleted");
        }

        synchronized (cache) {
            long now = System.currentTimeMillis();

            for (Enumeration en = add.elements(); en.hasMoreElements();) {
                Node n = (Node) en.nextElement();
                DbMapping dbm = app.getDbMapping(n.getPrototype());

                if (dbm != null) {
                    dbm.setLastDataChange(now);
                }

                n.setDbMapping(dbm);
                n.nmgr = safe;

                if (dbm != null && dbm.evictOnReplication()) {
                    Node oldNode = (Node) cache.get(n.getKey());

                    if (oldNode != null) {
                        evictNode(oldNode);
                    }
                } else {
                    n.lastParentSet = -1;
                    cache.put(n.getKey(), n);
                }
            }

            for (Enumeration en = delete.elements(); en.hasMoreElements();) {
                // NOTE: it would be more efficient to transfer just the keys
                // of nodes that are to be deleted.
                Node n = (Node) en.nextElement();
                DbMapping dbm = app.getDbMapping(n.getPrototype());

                if (dbm != null) {
                    dbm.setLastDataChange(now);
                }

                n.setDbMapping(dbm);
                n.nmgr = safe;

                Node oldNode = (Node) cache.get(n.getKey());

                if (oldNode != null) {
                    evictNode(oldNode);
                }
            }
        }
    }

    private void setStatementValues (PreparedStatement stmt, int stmtNumber, Property p, int columnType) throws SQLException {
        if (p.getValue() == null) {
            stmt.setNull(stmtNumber, columnType);
        } else {
            switch (columnType) {
                case Types.BIT:
                case Types.TINYINT:
                case Types.BIGINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                    stmt.setLong(stmtNumber, p.getIntegerValue());

                    break;

                case Types.REAL:
                case Types.FLOAT:
                case Types.DOUBLE:
                case Types.NUMERIC:
                case Types.DECIMAL:
                    stmt.setDouble(stmtNumber, p.getFloatValue());

                    break;

                case Types.VARBINARY:
                case Types.BINARY:
                case Types.BLOB:
                    stmt.setString(stmtNumber, p.getStringValue());

                    break;

                case Types.LONGVARBINARY:
                case Types.LONGVARCHAR:
                    try {
                        stmt.setString(stmtNumber, p.getStringValue());
                    } catch (SQLException x) {
                        String str = p.getStringValue();
                        Reader r = new StringReader(str);

                        stmt.setCharacterStream(stmtNumber, r,
                                                str.length());
                    }

                    break;

                case Types.CLOB:
                    String val = p.getStringValue();
                    Reader isr = new StringReader (val);
                    stmt.setCharacterStream (stmtNumber,isr, val.length());
                    
                    break;

                case Types.CHAR:
                case Types.VARCHAR:
                case Types.OTHER:
                    stmt.setString(stmtNumber, p.getStringValue());

                    break;

                case Types.DATE:
                case Types.TIME:
                case Types.TIMESTAMP:
                    stmt.setTimestamp(stmtNumber, p.getTimestampValue());

                    break;

                case Types.NULL:
                    stmt.setNull(stmtNumber, 0);

                    break;

                default:
                    stmt.setString(stmtNumber, p.getStringValue());

                    break;
            }
        }
    }

    private void logSqlStatement(String type, String table,
                                 long logTimeStart, long logTimeStop, String statement) {
        // init sql-log if necessary
        if (sqlLog == null) {
            String sqlLogName = app.getProperty("sqlLog", "helma."+app.getName()+".sql");
            sqlLog = LogFactory.getLog(sqlLogName);
        }

        sqlLog.info(new StringBuffer().append(type)
                                      .append(" ")
                                      .append(table)
                                      .append(" ")
                                      .append((logTimeStop - logTimeStart))
                                      .append(": ")
                                      .append(statement)
                                      .toString());
    }
}

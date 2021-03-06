/*
 * Copyright 2018 Jobsz (zcq@zhucongqi.cn)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/
package com.jfinal.ext.plugin.activerecord;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.Lists;
import com.jfinal.ext.kit.SqlpKit;
import com.jfinal.ext.plugin.redis.ModelRedisMapping;
import com.jfinal.kit.HashKit;
import com.jfinal.kit.StrKit;
import com.jfinal.plugin.activerecord.Db;
import com.jfinal.plugin.activerecord.Model;
import com.jfinal.plugin.activerecord.Record;
import com.jfinal.plugin.activerecord.SqlPara;
import com.jfinal.plugin.activerecord.Table;
import com.jfinal.plugin.redis.Cache;
import com.jfinal.plugin.redis.Redis;

public abstract class ModelExt<M extends ModelExt<M>> extends Model<M> {

	private static final long serialVersionUID = -6061985137398460903L;

	private static final String RECORDS = "records:";
	//default sync to redis
	private boolean syncToRedis = GlobalSyncRedis.syncState();
	private String cacheName = null;
	//call back
    private List<CallbackListener> callbackListeners = Lists.newArrayList();
    
    /**
     * add Call back listener
     * @param callbackListener
     */
    public void addCallbackListener(CallbackListener callbackListener) {
        this.callbackListeners.add(callbackListener);
    }

	/**
	 * redis key: records:tablename: id1 | id2 | id3...
	 * eg: the user has three primarykeys id1=1,id2=2,id3=3, so the redisKey is 'records:user:1|2|3'.
	 * @return redis key
	 */
	private String redisKey(ModelExt<?> m) {
		Table table = m.table();
		StringBuilder key = new StringBuilder();
		key.append(RECORDS);
		key.append(table.getName());
		key.append(":");
		//fetch primary keys' values
		String[] primaryKeys = table.getPrimaryKey();
		//format key
		for (int idx = 0; idx < primaryKeys.length; idx++) {
			Object primaryKeyVal = m.get(primaryKeys[idx]);
			if (null != primaryKeyVal) {
				if (idx > 0) {
					key.append("|");
				}
				key.append(primaryKeyVal);
			}
		}
		return key.toString();
	}
	
	/**
	 * redis key for attrs' values
	 * @param flag : ids or id store
	 * @return data[s]:md5(concat(columns' value))
	 */
	private String redisColumnKey(SqlpKit.FLAG flag) {
		StringBuilder key = new StringBuilder(this._getUsefulClass().toGenericString());
		String[] attrs = this._getAttrNames();
		Object val;
		for (String attr : attrs) {
			val = this.get(attr);
			if (null == val) {
				continue;
			}
			key.append(val.toString());
		}
		key = new StringBuilder(HashKit.md5(key.toString()));
		if (flag.equals(SqlpKit.FLAG.ONE)) {
			return "data:"+key;
		}
		return "datas:"+key;
	}

	protected void saveToRedis() {
		//save total data: generic save
		this.redis().set(this.redisKey(this), this.attrsCp());
	}
	
	private Cache redis() {
		Cache redis = null;
		if (StrKit.notBlank(this.cacheName)) {
			redis = GlobalSyncRedis.getSyncCache(this.cacheName);
		}
		
		if (null != redis) {
			return redis;
		}
		
		if (StrKit.isBlank(this.cacheName)) {
			this.cacheName = ModelRedisMapping.me().getCacheName(this.tableName());
		}
		
		if (StrKit.notBlank(this.cacheName)) {
			redis = Redis.use(this.cacheName);
		} else {
			redis = Redis.use();
		}
		if (null == redis) {
			throw new IllegalArgumentException(String.format("The Cache with the name '%s' was Not Found.", this.cacheName));	
		}
		//sync to memory.
		GlobalSyncRedis.setSyncCache(this.cacheName, redis);
		return redis;
	}
	
	/**
	 * Use the columns that must contains primary keys fetch Data from db, and use the fetched primary keys fetch from redis.
	 * @param columns
	 */
	private List<M> fetchDatasFromRedis(String... columns) {
		// redis key
		String key = this.redisColumnKey(SqlpKit.FLAG.ALL);
		// fetch from redis
		List<M> fetchDatas = this.redis().get(key);
		if (null == fetchDatas) {
			// fetch ids from db.
			fetchDatas = this.find(SqlpKit.select(this, this.primaryKeys()));
		}
		
		if (null == fetchDatas || fetchDatas.size() == 0) {
			return fetchDatas;
		}
		//put ids to redis 
		this.redis().setex(key, GlobalSyncRedis.syncExpire(), fetchDatas);
		for (M m : fetchDatas) {
			// fetch data from redis
			if (null == m) {
				continue;
			}
			m = this.fetchOne(m, columns);
		}
		return fetchDatas;
	}
	
	private M fetchOneFromRedis(String... columns) {
		Object pk = this.primaryKeyValue();
		if (null != pk) {
			return this.fetchOne(this, columns);
		}
		// redis key
		String key = this.redisColumnKey(SqlpKit.FLAG.ONE);
		// fetch from redis
		M m = this.redis().get(key);
		if (null == m) {
			// fetch id from db.
			m = this.findFirst(SqlpKit.selectOne(this, this.primaryKeys()));
		}
		if (null == m) {
			return m;
		}
		//put id to redis 
		this.redis().setex(key, GlobalSyncRedis.syncExpire(), m);
		return this.fetchOne(m, columns);
	}

	@SuppressWarnings("unchecked")
	private M fetchOne(ModelExt<?> m, String... columns) {
		// use primay key fetch from redis
		Map<String, Object> attrs = this.redis().get(this.redisKey(m));
		if (null != attrs) {
			if (null == columns || columns.length == 0) {
				return (M)m.put(attrs);
			}
			return (M)m.put(attrs).keep(columns);
		}
		// fetch from db
		M tmp = this.findFirst(SqlpKit.selectOne(m));
		// save to redis
		if (null != tmp) {
			if (null == columns || columns.length == 0) {
				m.put(tmp._getAttrs());
			} else {
				m.put(tmp._getAttrs()).keep(columns);
			}
			tmp.saveToRedis();
		}
		return (M)m;
	}
	
	/**
	 * Get attr type
	 * @param attr
	 * @return attr type class
	 */
	public Class<?> attrType(String attr) {
		Table table = this.table();
		if (null != table) {
			return table.getColumnType(attr);
		}
		
		Method[] methods = this._getUsefulClass().getMethods();
		String methodName;
		for (Method method : methods) {
			methodName = method.getName();
			if (methodName.startsWith("set") && methodName.substring(3).toLowerCase().equals(attr)) {
				return method.getParameterTypes()[0];
			}
		}
		return null;
	}
	
	/**
	 * Get attr names
	 */
	public List<String> attrNames() {
		String[] names = this._getAttrNames();
		if (null != names && names.length != 0) {
			return Arrays.asList(names);
		}
		Method[] methods = this._getUsefulClass().getMethods();
		String methodName;
		List<String> attrs = new ArrayList<String>();
		for (Method method : methods) {
			methodName = method.getName();
			if (methodName.startsWith("set") ) {
				String attr = methodName.substring(3).toLowerCase();
				if (StrKit.notBlank(attr)) {
					attrs.add(attr);
				}
			}
		}
		return attrs;
	}

	/**
	 * current instance is Null or not.
	 */
	public boolean _isNull() {
		return this._getAttrs().isEmpty();
	}
	
	/**
	 * Model attr copy version , the model just use default "DbKit.brokenConfig"
	 */
	public Map<Object, Object> attrsCp() {
		Map<Object, Object> attrs = new HashMap<Object, Object>();
		String[] attrNames = this._getAttrNames();
		for (String attr : attrNames) {
			attrs.put(attr, this.get(attr));
		}
		return attrs;
	}
	
	/**
	 * Model Attrs
	 */
	public Map<String, Object> attrs() {
		return this._getAttrs();
	}

	/**
	 * auto sync to the redis: true-sync,false-cancel, default true
	 * @param syncToRedis
	 */
	public void syncToRedis(boolean syncToRedis) {
		this.syncToRedis = syncToRedis;
	}

	/**
	 * shot cache's name.
	 * if current cacheName != the old cacheName, will reset old cache, update cache use the current cacheName and open syncToRedis.
	 */
	public void shotCacheName(String cacheName) {
		//reset cache
		if (StrKit.notBlank(cacheName) && !cacheName.equals(this.cacheName)) {
			GlobalSyncRedis.removeSyncCache(this.cacheName);
		}
		this.cacheName = cacheName;
		//auto open sync to redis
		this.syncToRedis = true;
		//update model redis mapping
		ModelRedisMapping.me().put(this.tableName(), this.cacheName);
	}
	
	/**
	 * get current model's table
	 */
	public Table table() {
		return this._getTable();
	}

	/**
	 * get current model's tablename
	 */
	public String tableName() {
		return this.table().getName();
	}
	
	/**
	 * All primary keys
	 */
	public String[] primaryKeys() {
		return this.table().getPrimaryKey();
	}

	/**
	 * get numeric primary key.
	 * if there is not found the primary key will throw the IllegalArgumentException.
	 */
	public String primaryKey() {
		String[] primaryKeys = this.primaryKeys();
		if (primaryKeys.length >= 1) {
			return primaryKeys[0];
		}
		throw new IllegalArgumentException("Not Found ,The Primary Key[s].");
	}
	
	/**
	 * get primary key value
	 * if there is not found the primary key will throw the IllegalArgumentException.
	 */
	public Object primaryKeyValue() {
		return this.get(this.primaryKey());
	}

	/**
	 * save to db, if `syncToRedis` is true , this model will save to redis in the same time.
	 */
	@Override
	public boolean save() {
		for (CallbackListener callbackListener : this.callbackListeners) {
			callbackListener.beforeSave(this);
		}
		boolean ret = super.save();
		if (this.syncToRedis && ret) {
			this.saveToRedis();
		}
		for (CallbackListener callbackListener : this.callbackListeners) {
			callbackListener.afterSave(this);
		}
		return ret;
	}

	/**
	 * delete from db, if `syncToRedis` is true , this model will delete from redis in the same time.
	 */
	@Override
	public boolean delete() {
        for (CallbackListener callbackListener : this.callbackListeners) {
            callbackListener.beforeDelete(this);
        }
		boolean ret = super.delete();
		if (this.syncToRedis && ret) {
			//delete from Redis
			this.redis().del(this.redisKey(this));
		}
        for (CallbackListener callbackListener : this.callbackListeners) {
            callbackListener.afterDelete(this);
        }
		return ret;
	}

	/**
	 * update db, if `syncToRedis` is true , this model will update redis in the same time.
	 */
	@Override
	public boolean update() {
        for (CallbackListener callbackListener : this.callbackListeners) {
            callbackListener.beforeUpdate(this);
        }
		boolean ret = super.update();
		if (this.syncToRedis && ret) {
			this.saveToRedis();
		}
        for (CallbackListener callbackListener : this.callbackListeners) {
            callbackListener.afterUpdate(this);
        }
		return ret;
	}

	/**
	 * <b>Advanced Function</b>. 
	 * List All data. <br/>
	 * 1. You can use any column value fetch Models;<br/>
	 * 2. The fetched Models contains all columns, <br/>
	 * 3. if `syncToRedis` is true, the data from Redis else from DB.<br/>
	 */
	public List<M> fetch() {
		//from db
		if (!this.syncToRedis) {
			return this.find(SqlpKit.select(this));
		}
		//from redis
		return this.fetchDatasFromRedis();
	}

	/**
	 * <b>Advanced Function</b>. 
	 * List All data. <br/>
	 * 1. The data from DB just contains `columns` and primary keys; <br/>
	 * 2. The data from Redis will contain all columns. <br/>
	 * 3. Use any column value can do this. <br/>
	 * @param columns: will fetch columns
	 */
	public List<M> fetch(String... columns) {
		if (null == columns) {
			return this.fetch();
		}
		//from db
		if (!this.syncToRedis) {
			return this.find(SqlpKit.select(this, columns));
		}
		//from redis
		return this.fetchDatasFromRedis(columns);
	}
	
	/**
	 * <b>Advanced Function</b>. 
	 * you can fetch FirstOne Model: use any column value can do this.the data from DB.
	 */
	public M fetchOne() {
		//from db
		if (!this.syncToRedis) {
			return this.findFirst(SqlpKit.selectOne(this));
		}
		//from redis
		return this.fetchOneFromRedis();
	}
	
	/**
	 * <b>Advanced Function</b>. 
	 * you can fetch FirstOne Model:just contains columns, use any column value can do this.the data from DB.
	 */
	public M fetchOne(String... columns) {
		if (null == columns) {
			return this.fetchOne();
		}
		//from db
		if (!this.syncToRedis) {
			return this.findFirst(SqlpKit.selectOne(this, columns));
		}
		//from redis
		return this.fetchOneFromRedis(columns);
	}
	
	/**
	 * List All data just contains the primary keys.
	 */
	public List<M> fetchPrimaryKeysOnly() {
		return this.find(SqlpKit.select(this, this.primaryKeys()));
	}
	
	/**
	 * Data Count
	 */
	public Long dataCount() {
		SqlPara sql = SqlpKit.select(this, "count(*) AS cnt");
		Record record = Db.findFirst(sql);
		if (null != record) {
			return record.get("cnt");
		}
		return 0L;
	}

	@SuppressWarnings("unchecked")
	private M cp(boolean cpAttrs) {
		M m = null;
		try {
			m = (M) this._getUsefulClass().newInstance();
		} catch (InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
		}
		if (cpAttrs) {
			m.put(this._getAttrs());
		}
		return m;
	}
	

	/**
	 * Clone new instance[wrapper clone]
	 */
	public M cp() {
		return this.cp(true);
	}
	
	/**
	 * Clone new instance[wrapper clone], just link attrs values
	 * @param attrs
	 */
	public M cp(String... attrs) {
		M m = this.cp(false);
		for (String attr : attrs) {
			m.set(attr, this.get(attr));
		}
		return m;
	}
	
	/**
	 * check current instance is equal obj or not.[wrapper equal]
	 * @param obj
	 * @return true:equal, false:not equal.
	 */
	public boolean eq(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (this._getUsefulClass() != obj.getClass())
            return false;
        
        Model<?> other = (Model<?>) obj;
        Table tableinfo = this.table();
        Set<Entry<String, Object>> attrsEntrySet = this._getAttrsEntrySet();
        for (Entry<String, Object> entry : attrsEntrySet) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Class<?> clazz = tableinfo.getColumnType(key);
            if (clazz == Float.class) {
            } else if (clazz == Double.class) {
            } else if (clazz == Model.class) {
            } else {
                if (value == null) {
                    if (other.get(key) != null)
                        return false;
                } else if (!value.equals(other.get(key)))
                    return false;
            }
        }
        return true;		
	}
	
	/**
	 * wrapper hash code
	 */
	public int hcode() {
		final int prime = 31;
		int result = 1;
		Table table = this.table();
		Set<Entry<String, Object>> attrsEntrySet = this._getAttrsEntrySet();
		for (Entry<String, Object> entry : attrsEntrySet) {
			String key = entry.getKey();
			Object value = entry.getValue();
			Class<?> clazz = table.getColumnType(key);
			if (clazz == Integer.class) {
				result = prime * result + (Integer) value;
			} else if (clazz == Short.class) {
				result = prime * result + (Short) value;
			} else if (clazz == Long.class) {
				result = prime * result + (int) ((Long) value ^ ((Long) value >>> 32));
			} else if (clazz == Float.class) {
				result = prime * result + Float.floatToIntBits((Float) value);
			} else if (clazz == Double.class) {
				long temp = Double.doubleToLongBits((Double) value);
				result = prime * result + (int) (temp ^ (temp >>> 32));
			} else if (clazz == Boolean.class) {
				result = prime * result + ((Boolean) value ? 1231 : 1237);
			} else if (clazz == Model.class) {
				result = this.hcode();
			} else {
				result = prime * result + ((value == null) ? 0 : value.hashCode());
			}
		}
		return result;
	}
	
	/**
	 * Convert to Record
	 */
	public Record convertToRecord() {
		Record r = new Record();
		r.setColumns(this._getAttrs());
		return r;
	}
}

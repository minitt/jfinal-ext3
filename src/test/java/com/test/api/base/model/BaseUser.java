package com.test.api.base.model;

import com.jfinal.ext.plugin.activerecord.ModelExt;
import com.jfinal.plugin.activerecord.IBean;

/**
 * Generated by JFinal, do not modify this file.
 */
@SuppressWarnings({"serial", "unchecked"})
public abstract class BaseUser<M extends BaseUser<M>> extends ModelExt<M> implements IBean {

	public M setName(java.lang.String name) {
		set("name", name);
		return (M)this;
	}
	
	public java.lang.String getName() {
		return getStr("name");
	}

	public M setAddr(java.lang.String addr) {
		set("addr", addr);
		return (M)this;
	}
	
	public java.lang.String getAddr() {
		return getStr("addr");
	}

	public M setId(java.lang.Integer id) {
		set("id", id);
		return (M)this;
	}
	
	public java.lang.Integer getId() {
		return getInt("id");
	}

}

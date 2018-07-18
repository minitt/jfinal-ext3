package com.test.api.base.model;

import com.jfinal.ext.plugin.activerecord.ModelExt;
import com.jfinal.plugin.activerecord.IBean;

/**
 * Generated by JFinal, do not modify this file.
 */
@SuppressWarnings({"serial", "unchecked"})
public abstract class BaseHello<M extends BaseHello<M>> extends ModelExt<M> implements IBean {

	public M setId(java.lang.Integer id) {
		set("id", id);
		return (M)this;
	}
	
	public java.lang.Integer getId() {
		return getInt("id");
	}

	public M setName(java.lang.String name) {
		set("name", name);
		return (M)this;
	}
	
	public java.lang.String getName() {
		return getStr("name");
	}

	public M setHello(java.lang.String hello) {
		set("hello", hello);
		return (M)this;
	}
	
	public java.lang.String getHello() {
		return getStr("hello");
	}

}
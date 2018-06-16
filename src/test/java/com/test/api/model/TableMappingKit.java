package com.test.api.model;

import com.jfinal.plugin.activerecord.ActiveRecordPlugin;

/**
 * Generated by JFinal, do not modify this file.
 * <pre>
 * Example:
 * public void configPlugin(Plugins me) {
 *     ActiveRecordPlugin arp = new ActiveRecordPlugin(...);
 *     TableMappingKit.mapping(arp);
 *     me.add(arp);
 * }
 * </pre>
 */
public class TableMappingKit {
	
	public static void mapping(ActiveRecordPlugin arp) {
		arp.addMapping("user", "id", User.class);
	}
}


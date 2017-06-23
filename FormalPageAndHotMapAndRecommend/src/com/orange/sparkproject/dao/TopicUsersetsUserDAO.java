package com.orange.sparkproject.dao;

import java.util.List;

import com.orange.sparkproject.domain.TopicUsersetsUser;

/**
 * 插入实时数据的DAO接口
 * @author Administrator
 *
 */
public interface TopicUsersetsUserDAO {

	void insert(TopicUsersetsUser topicUsersetsUser);
	
	void insertBatch(List<TopicUsersetsUser> topicUsersetsUser);
	
}

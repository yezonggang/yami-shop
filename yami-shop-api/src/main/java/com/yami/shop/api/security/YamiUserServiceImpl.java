/*
 * Copyright (c) 2018-2999 广州亚米信息科技有限公司 All rights reserved.
 *
 * https://www.gz-yami.com/
 *
 * 未经允许，不可做商业用途！
 *
 * 版权所有，侵权必究！
 */

package com.yami.shop.api.security;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.emoji.EmojiUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yami.shop.bean.model.User;
import com.yami.shop.common.annotation.RedisLock;
import com.yami.shop.common.exception.YamiShopBindException;
import com.yami.shop.common.util.CacheManagerUtil;
import com.yami.shop.common.util.PrincipalUtil;
import com.yami.shop.dao.UserMapper;
import com.yami.shop.security.dao.AppConnectMapper;
import com.yami.shop.security.enums.App;
import com.yami.shop.security.exception.UsernameNotFoundException;
import com.yami.shop.security.exception.UsernameNotFoundExceptionBase;
import com.yami.shop.security.model.AppConnect;
import com.yami.shop.security.service.YamiUser;
import com.yami.shop.security.service.YamiUserDetailsService;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户详细信息
 *
 * @author
 */
@Slf4j
@Service
@AllArgsConstructor
public class YamiUserServiceImpl implements YamiUserDetailsService {

	private final UserMapper userMapper;

	private final AppConnectMapper appConnectMapper;

	private final PasswordEncoder passwordEncoder;
	@Override
	@SneakyThrows
	public YamiUser loadUserByUsername(String username) {
		if (StrUtil.isBlank(username) || !username.contains(StrUtil.COLON) ) {
			throw new UsernameNotFoundExceptionBase("无法获取用户信息");
		}
		String[] splitInfo = username.split(StrUtil.COLON);
		App app = App.instance(Integer.valueOf(splitInfo[0]));
		String bizUserId = splitInfo[1];
		return loadUserByAppIdAndBizUserId(app,bizUserId);
	}

	/**
	 * 获取前端登陆的用户信息
	 *
	 * @param app
	 * @param bizUserId openId
	 * @return UserDetails
	 * @throws UsernameNotFoundExceptionBase
	 */
	@Override
	public YamiUser loadUserByAppIdAndBizUserId(App app, String bizUserId) {

		String cacheKey = app.value() + StrUtil.COLON + bizUserId;

		User user = userMapper.getUserByBizUserId(app.value(), bizUserId);
		if (user == null) {
			throw new UsernameNotFoundExceptionBase("无法获取用户信息");
		}
		String name = StrUtil.isBlank(user.getRealName()) ? user.getNickName() : user.getRealName();
		YamiUser yamiUser = new YamiUser(user.getUserId(), bizUserId, app.value(), user.getStatus() == 1);
		yamiUser.setName(name);
		yamiUser.setPic(user.getPic());

		return yamiUser;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	@RedisLock(lockName = "insertUser", key = "#appConnect.appId + ':' + #appConnect.bizUserId")
	@Caching(evict = {
			@CacheEvict(cacheNames = "yami_user", key = "#appConnect.appId + ':' + #appConnect.bizUserId"),
			@CacheEvict(cacheNames = "AppConnect", key = "#appConnect.appId + ':' + #appConnect.bizUserId")
	})
	public void insertUserIfNecessary(AppConnect appConnect) {
		// 进入锁后再重新判断一遍用户是否创建
		AppConnect dbAppConnect = appConnectMapper.getByBizUserId(appConnect.getBizUserId(), appConnect.getAppId());
		if(dbAppConnect != null) {
			return;
		}

		String bizUnionId = appConnect.getBizUnionid();
		String userId = null;
		User user;

		if (StrUtil.isNotBlank(bizUnionId)) {
			userId = appConnectMapper.getUserIdByUnionId(bizUnionId);
		}
		if (StrUtil.isBlank(userId)) {
			userId = IdUtil.simpleUUID();
			Date now = new Date();
			user = new User();
			user.setUserId(userId);
			user.setModifyTime(now);
			user.setUserRegtime(now);
			user.setStatus(1);
			user.setNickName(EmojiUtil.toAlias(StrUtil.isBlank(appConnect.getNickName()) ? "" : appConnect.getNickName()));
			user.setPic(appConnect.getImageUrl());
			userMapper.insert(user);
		} else {
			user = userMapper.selectById(userId);
		}

		appConnect.setUserId(user.getUserId());

		appConnectMapper.insert(appConnect);
	}

	@Override
	public YamiUser loadUserByUserMail(String userMail, String loginPassword) {
		User user = userMapper.getUserByUserMail(userMail);
		if (user == null) {
			throw new UsernameNotFoundException("用户不存在");
		}

		if (!passwordEncoder.matches(loginPassword, user.getLoginPassword())) {
			// 原密码不正确
			throw new UsernameNotFoundException("密码不正确");
		}
		String name = StrUtil.isBlank(user.getRealName()) ? user.getNickName() : user.getRealName();
		YamiUser yamiUser = new YamiUser(user.getUserId(), loginPassword, user.getStatus() == 1);
		yamiUser.setName(name);
		yamiUser.setPic(user.getPic());
		return yamiUser;
	}

	@Override
	public User loadUserByMobileOrUserName(String mobileOrUserName, Integer loginType) {
		User user = null;
		// 手机验证码登陆，或传过来的账号很像手机号
		if (Objects.equals(loginType, 1) || PrincipalUtil.isMobile(mobileOrUserName)) {
			user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserMobile, mobileOrUserName));
		}
		return user;
	}

	@Override
	public YamiUser getYamiUser(Integer appId, User user, String bizUserId) {
		String name = StrUtil.isBlank(user.getRealName()) ? user.getNickName() : user.getRealName();
		YamiUser yamiUser = new YamiUser();
		yamiUser.setEnabled(user.getStatus() == 1);
		yamiUser.setUserId(user.getUserId());
		yamiUser.setBizUserId(bizUserId);
		yamiUser.setAppType(appId);
		yamiUser.setName(name);
		yamiUser.setPic(user.getPic());
		yamiUser.setPassword(user.getLoginPassword());
		return yamiUser;
	}
}

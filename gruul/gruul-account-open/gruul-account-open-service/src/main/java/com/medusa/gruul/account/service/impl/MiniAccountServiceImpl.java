package com.medusa.gruul.account.service.impl;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import cn.binarywang.wx.miniapp.util.crypt.WxMaCryptUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.extra.qrcode.QrConfig;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.medusa.gruul.account.api.conf.MiniInfoProperty;
import com.medusa.gruul.account.api.entity.*;
import com.medusa.gruul.account.api.enums.BlacklistEnum;
import com.medusa.gruul.account.api.enums.OauthTypeEnum;
import com.medusa.gruul.account.api.model.*;
import com.medusa.gruul.account.conf.AccountRedis;
import com.medusa.gruul.account.conf.SnowflakeProperty;
import com.medusa.gruul.account.constant.RedisConstant;
import com.medusa.gruul.account.mapper.MiniAccountMapper;
import com.medusa.gruul.account.model.dto.*;
import com.medusa.gruul.account.model.vo.*;
import com.medusa.gruul.account.service.*;
import com.medusa.gruul.common.core.constant.CommonConstants;
import com.medusa.gruul.common.core.constant.TimeConstants;
import com.medusa.gruul.common.core.constant.enums.LoginTerminalEnum;
import com.medusa.gruul.common.core.exception.ServiceException;
import com.medusa.gruul.common.core.util.*;
import com.medusa.gruul.common.dto.CurMiniUserInfoDto;
import com.medusa.gruul.common.dto.CurUserDto;
import com.medusa.gruul.platform.api.model.dto.LoginDto;
import com.medusa.gruul.shops.api.entity.ShopsPartner;
import com.medusa.gruul.shops.api.feign.RemoteShopsService;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * <p>
 * ?????????????????? ???????????????
 * </p>
 *
 * @author whh
 * @since 2019-11-18
 */
@Service
@Slf4j
public class MiniAccountServiceImpl extends ServiceImpl<MiniAccountMapper, MiniAccount> implements IMiniAccountService {

    @Autowired
    private MiniInfoProperty miniInfoProperty;
    @Autowired
    private SnowflakeProperty snowflakeProperty;
    @Autowired
    private IMiniAccountOauthsService miniAccountOauthsService;
    @Autowired
    private IMiniAccountExtendsService miniAccountExtendsService;
    @Autowired
    private IMiniAccountTagService miniAccountTagService;
    @Autowired
    private IMiniAccountTagGroupService miniAccountTagGroupService;
    @Autowired
    private IMiniAccountAddressService iMiniAccountAddressService;
    @Autowired
    private IMiniAccountRestrictService miniAccountRestrictService;


    /**
     * ?????????????????????
     *
     * @return
     */
    public WxMaService getWxMaService() {
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();
        config.setAppid(miniInfoProperty.getAppId());
        config.setSecret(miniInfoProperty.getSecret());
        config.setMsgDataFormat("JSON");
        WxMaService wxMaService = new WxMaServiceImpl();
        wxMaService.setWxMaConfig(config);
        return wxMaService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginBaseInfoVo login(String code) {
        LocalDateTime currentDateTime = LocalDateTime.now();
        final WxMaService wxService = getWxMaService();
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("??????code?????????");
        }
        WxMaJscode2SessionResult session = null;
        try {
            session = wxService.getUserService().getSessionInfo(code);
            if (ObjectUtil.isEmpty(session)) {
                throw new ServiceException("login handler error");
            }
        } catch (WxErrorException e) {
            e.printStackTrace();
        }
        LoginDto login = new LoginDto();
        login.setOpenId(session.getOpenid());
        login.setUnionId(session.getUnionid());
        login.setSessionKey(session.getSessionKey());
        if (login == null || login.getOpenId() == null) {
            throw new ServiceException("????????????,????????????", SystemCode.DATA_EXISTED.getCode());
        }
        String loginKey = RedisConstant.LOGIN_KEY.concat(":")
                .concat(OauthTypeEnum.WX_MINI.getType().toString()).concat(":").concat(login.getOpenId());
        AccountRedis accountRedis = new AccountRedis();
        //???????????????????????????
        String loginBase = accountRedis.get(loginKey);
        MiniAccountOauthsDto miniAccountOauthsDto = null;
        if (StrUtil.isNotEmpty(loginBase)) {
            miniAccountOauthsDto = JSON.parseObject(loginBase, MiniAccountOauthsDto.class);
        } else {
            MiniAccountOauths miniAccountOauths = null;
            if (StrUtil.isNotBlank(login.getUnionId())) {
                miniAccountOauths = miniAccountOauthsService.getByUnionIdAndType(login.getUnionId(), OauthTypeEnum.WX_MINI);
            }
            //??????????????????????????????????????????????????????openId???????????????????????????
            if (miniAccountOauths == null) {
                miniAccountOauths = miniAccountOauthsService.getByOpenId(login.getOpenId(), OauthTypeEnum.WX_MINI.getType());
                //UNiconId??????????????????????????????
                if (StrUtil.isNotBlank(login.getUnionId()) && miniAccountOauths != null && !login.getUnionId().equals(miniAccountOauths.getUnionId())) {
                    miniAccountOauths.setUnionId(login.getUnionId());
                    miniAccountOauthsService.updateById(miniAccountOauths);

                }
            }
            MiniAccount account = null;
            //???????????????????????????????????????????????????????????????????????????????????????
            if (miniAccountOauths != null) {
                account = this.getByUserId(miniAccountOauths.getUserId());
                //??????????????????????????????????????????????????????????????????????????????????????????????????????,???????????????????????????????????????
                if (miniAccountOauths.getOauthType().equals(OauthTypeEnum.WX_MP.getType())) {
                    miniAccountOauths = new MiniAccountOauths();
                    miniAccountOauths.setOauthType(OauthTypeEnum.WX_MINI.getType());
                    miniAccountOauths.setUserId(account.getUserId());
                    miniAccountOauths.setOpenId(login.getOpenId());
                    miniAccountOauths.setUnionId(login.getUnionId());
                    miniAccountOauthsService.save(miniAccountOauths);

                }
                //??????????????????
                MiniAccountExtends accountExtends = miniAccountExtendsService.findByCurrentStatus(miniAccountOauths.getUserId());
                miniAccountOauthsDto = accuontOauthsCache(loginKey, miniAccountOauths, account, accountExtends);
            }
        }
        //?????????,???????????????
        if (miniAccountOauthsDto == null) {
            miniAccountOauthsDto = initMiniInfo(currentDateTime, login, loginKey);
        }
        String userId = miniAccountOauthsDto.getUserId();
        CompletableFuture.runAsync(() -> {
            //??????????????????????????????
            MiniAccountExtends accountExtends = miniAccountExtendsService.findByCurrentStatus(userId);
            miniAccountExtendsService.update(new UpdateWrapper<MiniAccountExtends>()
                    .set("last_login_time", currentDateTime).eq("shop_user_id", accountExtends.getShopUserId()));
        }).exceptionally(throwable -> {
            throwable.printStackTrace();
            return null;
        });
        //??????????????????
        LoginBaseInfoVo vo = new LoginBaseInfoVo();
        vo.setSessionKey(login.getSessionKey());
        vo.setToken(RedisConstant.ACCOUNT_KEY.concat(miniAccountOauthsDto.getToken()));
        vo.setWhetherAuthorization(getUserWhetherAuthorization(miniAccountOauthsDto.getUserId()));
        return vo;
    }

    /**
     * ????????????????????????
     *
     * @param currentDateTime ????????????
     * @param login           com.medusa.gruul.platform.api.model.dto.LoginDto
     * @param loginKey        ket
     * @return com.medusa.gruul.account.model.dto.MiniAccountOauthsDto
     */
    private MiniAccountOauthsDto initMiniInfo(LocalDateTime currentDateTime, LoginDto login, String loginKey) {
        log.info("??????initMiniInfo".concat(login.toString()));
        System.out.println("??????initMiniInfo".concat(login.toString()));
        MiniAccountOauthsDto miniAccountOauthsDto;
        Snowflake snowflake = IdUtil.createSnowflake(snowflakeProperty.getWorkerId(), snowflakeProperty.getDatacenterId());
        String userId = snowflake.nextId() + "";
        MiniAccount account = new MiniAccount();
        account.setUserId(userId);
        account.setFirstLoginTime(currentDateTime);
        account.setWhetherAuthorization(false);
        this.getBaseMapper().insert(account);
        //??????????????????
        MiniAccountOauths miniAccountOauths = new MiniAccountOauths();
        miniAccountOauths.setOauthType(OauthTypeEnum.WX_MINI.getType());
        miniAccountOauths.setUserId(account.getUserId());
        miniAccountOauths.setOpenId(login.getOpenId());
        miniAccountOauths.setUnionId(login.getUnionId());
        miniAccountOauthsService.save(miniAccountOauths);
        //??????????????????
        MiniAccountExtends miniAccountExtends = new MiniAccountExtends();
        miniAccountExtends.setUserId(account.getUserId());
        miniAccountExtends.setIsBlacklist(0);
        miniAccountExtends.setConsumeNum(0);
        miniAccountExtends.setCurrentStatus(CommonConstants.NUMBER_ONE);
        miniAccountExtends.setConsumeTotleMoney(BigDecimal.valueOf(0.0));
        miniAccountExtends.setShopUserId(snowflake.nextId() + "");
        miniAccountExtendsService.save(miniAccountExtends);
        miniAccountOauthsDto = accuontOauthsCache(loginKey, miniAccountOauths, account, miniAccountExtends);
        return miniAccountOauthsDto;
    }


    /**
     * ????????????????????????
     *
     * @param loginKey       rediskey
     * @param accountOauths  ??????????????????
     * @param account        ??????????????????
     * @param accountExtends ??????????????????
     * @return com.medusa.gruul.account.model.dto.MiniAccountOauthsDto
     */
    private MiniAccountOauthsDto accuontOauthsCache(String loginKey, MiniAccountOauths accountOauths, MiniAccount account, MiniAccountExtends accountExtends) {
        MiniAccountOauthsDto miniAccountOauthsDto = null;
        AccountRedis accountRedis = new AccountRedis();
        String cacheUserInfo = accountRedis.get(loginKey);
        //??????????????????token??????????????????????????????token??????
        if (StrUtil.isNotEmpty(cacheUserInfo)) {
            miniAccountOauthsDto = JSONObject.parseObject(cacheUserInfo, MiniAccountOauthsDto.class);
        } else {
            miniAccountOauthsDto = new MiniAccountOauthsDto();
            BeanUtils.copyProperties(accountOauths, miniAccountOauthsDto);
            String jwtToken = new JwtUtils("gruul-mini-account").createJwtToken("mini-account");
            miniAccountOauthsDto.setToken(jwtToken);
            accountRedis.setNxPx(loginKey, JSON.toJSONString(miniAccountOauthsDto), TimeConstants.ONE_HOUR * 2);
            //????????????????????????????????????
            curUserCache(miniAccountOauthsDto, account, accountExtends);
        }
        return miniAccountOauthsDto;
    }

    /**
     * ???????????????????????????
     *
     * @param userId ??????id
     * @return ?????????  trun  ????????? false
     */
    private Boolean getUserWhetherAuthorization(String userId) {
        MiniAccount account = this.getByUserId(userId);
        return account.getWhetherAuthorization();
    }


    /**
     * ??????????????????????????????
     *
     * @param miniAccountOauthsDto com.medusa.gruul.account.model.dto.MiniAccountOauthsDto
     * @param account              ??????????????????
     * @param accountExtends       ??????????????????
     */
    private void curUserCache(MiniAccountOauthsDto miniAccountOauthsDto, MiniAccount account, MiniAccountExtends accountExtends) {
        AccountRedis allRedis = new AccountRedis(CommonConstants.MINI_ACCOUNT_REDIS_KEY);
        CurMiniUserInfoDto miniUserInfoDto = new CurMiniUserInfoDto();
        miniUserInfoDto.setUserId(account.getUserId());
        miniUserInfoDto.setNikeName(account.getNikeName());
        miniUserInfoDto.setAvatarUrl(account.getAvatarUrl());
        miniUserInfoDto.setGender(account.getGender());
        miniUserInfoDto.setShopUserId(accountExtends.getShopUserId());
        miniUserInfoDto.setOpenId(miniAccountOauthsDto.getOpenId());

        CurUserDto curUserDto = new CurUserDto();

//        1-???????????????,2-?????????
        if (miniAccountOauthsDto.getOauthType().equals(CommonConstants.NUMBER_ONE)) {
            curUserDto.setUserType(CommonConstants.NUMBER_ZERO);
            miniUserInfoDto.setTerminalType(LoginTerminalEnum.MINI);
        }
        if (miniAccountOauthsDto.getOauthType().equals(CommonConstants.NUMBER_TWO)) {
            curUserDto.setUserType(CommonConstants.NUMBER_TWO);
            miniUserInfoDto.setTerminalType(LoginTerminalEnum.H5);
        }
        allRedis.setNxPx(miniAccountOauthsDto.getToken(), JSON.toJSONString(miniUserInfoDto), TimeConstants.SIX_HOURS * 2);
        // curUserDto ??????
        curUserDto.setNikeName(account.getNikeName());
        curUserDto.setAvatarUrl(account.getAvatarUrl());
        curUserDto.setGender(account.getGender());
        curUserDto.setUserId(accountExtends.getShopUserId());
        curUserDto.setShopType(0);
        curUserDto.setVersion("");
        curUserDto.setOpenId(miniAccountOauthsDto.getOpenId());
        AccountRedis accountRedis = new AccountRedis();
        accountRedis.setNxPx(miniAccountOauthsDto.getToken(), JSON.toJSONString(curUserDto), TimeConstants.SIX_HOURS * 2);
    }


    @Override
    public MiniAccount getByUserId(String userId) {
        String key = RedisConstant.ACCOUNT_DB_KEY.concat(userId);
        AccountRedis accountRedis = new AccountRedis();
        String accountJson = accountRedis.get(key);
        MiniAccount account = null;
        if (StrUtil.isNotEmpty(accountJson)) {
            account = JSONObject.parseObject(accountJson, MiniAccount.class);
        } else {
            account = this.baseMapper.selectOne(new QueryWrapper<MiniAccount>().eq("user_id", userId));
            accountCache(account);
        }
        return account;
    }


    @Override
    public String decodePhoneInfo(DecodePhoneInfo decodePhoneInfo) {
        String json = WxMaCryptUtils.decrypt(decodePhoneInfo.getSessionKey(), decodePhoneInfo.getEncryptedData(), decodePhoneInfo.getIvStr());
        JSONObject jsonObject = JSON.parseObject(json);
        String phoneNumber = jsonObject.getString("purePhoneNumber");
        CurUserDto curUser = CurUserUtil.getHttpCurUser();
        MiniAccount miniAccount = this.getByUserId(curUser.getUserId());
        if (miniAccount == null) {
            throw new ServiceException("????????????", SystemCode.DATA_NOT_EXIST_CODE);
        }
        miniAccount.setPhone(phoneNumber);
        this.updateById(miniAccount);
        accountCache(miniAccount);
        return phoneNumber;
    }

    @Override
    public void updateUserBaseInfo(UpdateUserBaseInfoDto updateUserBaseInfoDto) {
        CurUserDto curUser = CurUserUtil.getHttpCurUser();
        MiniAccount miniAccount = this.getByShopUserId(curUser.getUserId());
        if (miniAccount == null) {
            throw new ServiceException("????????????", SystemCode.DATA_NOT_EXIST_CODE);
        }
        BeanUtil.copyProperties(updateUserBaseInfoDto, miniAccount, CopyOptions.create().ignoreNullValue());
        if (StrUtil.isEmpty(updateUserBaseInfoDto.getNikeName())) {
            miniAccount.setNikeName(updateUserBaseInfoDto.getNickName());
        }
        miniAccount.setWhetherAuthorization(Boolean.TRUE);
        this.updateById(miniAccount);
        //?????????????????????????????????
        accountCache(miniAccount);
    }

    /**
     * ????????????
     *
     * @param miniAccount com.medusa.gruul.account.api.entity.MiniAccount
     */
    private void accountCache(MiniAccount miniAccount) {
        String key = RedisConstant.ACCOUNT_DB_KEY.concat(miniAccount.getUserId());
        AccountRedis accountRedis = new AccountRedis();
        accountRedis.set(key, JSON.toJSONString(miniAccount));
        CompletableFuture.runAsync(() -> {
            MiniAccountOauths miniAccountOauths = miniAccountOauthsService.getByUserId(OauthTypeEnum.WX_MINI.getType(), miniAccount.getUserId());
            String loginKey = RedisConstant.LOGIN_KEY.concat(":").concat(OauthTypeEnum.WX_MINI.getType().toString()).concat(":").concat(miniAccountOauths.getOpenId());
            MiniAccountExtends accountExtends = miniAccountExtendsService.findByCurrentStatus(miniAccountOauths.getUserId());
            accuontOauthsCache(loginKey, miniAccountOauths, miniAccount, accountExtends);
        });
    }


    @Override
    public UserInfoVo getUserInfo(Integer infoLevel) {
        UserInfoVo vo = new UserInfoVo();
        CurUserDto curUser = CurUserUtil.getHttpCurUser();
        if (curUser == null) {
            throw new ServiceException("token??????", SystemCode.DATA_NOT_EXIST_CODE);
        }
        MiniAccountExtends miniAccountExtends = miniAccountExtendsService.findByShopUserId(curUser.getUserId());
        if (miniAccountExtends == null) {
            throw new ServiceException("????????????", SystemCode.DATA_NOT_EXIST_CODE);
        }
        MiniAccount miniAccount = this.getByUserId(miniAccountExtends.getUserId());
        BeanUtils.copyProperties(miniAccount, vo);
        vo.setShopUserId(curUser.getUserId());
        if (infoLevel.equals(CommonConstants.NUMBER_TWO)) {
            UserInfoExtendsVo extendsVo = new UserInfoExtendsVo();
            BeanUtils.copyProperties(miniAccountExtends, extendsVo);
            //??????????????????
            vo.setInfoExtends(extendsVo);
        }
        if (infoLevel.equals(CommonConstants.NUMBER_THREE)) {
            UserBlacklistAstrictVo astrictVo = new UserBlacklistAstrictVo();
            List<Integer> restrictTypes = miniAccountRestrictService.getByUserId(curUser.getUserId());
            if (CollectionUtil.isNotEmpty(restrictTypes)) {
                for (Integer restrictType : restrictTypes) {
                    if (restrictType.equals(BlacklistEnum.REJECT_ORDER.getType())) {
                        astrictVo.setRejectOrder(true);
                    }

                    if (restrictType.equals(BlacklistEnum.REJECT_COMMENT.getType())) {
                        astrictVo.setRejectOrder(true);
                    }
                }

            }
            vo.setAstrictVo(astrictVo);
        }

        return vo;
    }

    @Override
    public PageUtils<List<UserListVo>> userList(String nikeName,
                                                Long tagId,
                                                String orderSuccessStartTime, String orderSuccessEndTime,
                                                Integer page, Integer size, Integer sortType) {
        Map<String, Object> paramMap = new HashMap<>(8);
        if (tagId != null) {
            paramMap.put("tagId", tagId);
        }
        if (StrUtil.isNotEmpty(orderSuccessStartTime)) {
            paramMap.put("orderSuccessStartTime", orderSuccessStartTime);
        }
        if (StrUtil.isNotEmpty(orderSuccessEndTime)) {
            paramMap.put("orderSuccessEndTime", orderSuccessEndTime);
        }
        if (StrUtil.isNotBlank(nikeName)) {
            AtomicReference<String> name = new AtomicReference<>("%");
            nikeName.chars().mapToObj(obj -> (char) obj).forEach(obj -> {
                name.set(name.get().concat(obj.toString()).concat("%"));
            });
            paramMap.put("nikeName", name.toString());
        }
        if (sortType != null) {
            paramMap.put("sortType", sortType);
        }

        IPage<UserListDto> iPage = this.baseMapper.selectByUserList(new Page<>(page, size), paramMap);
        List<UserListDto> records = iPage.getRecords();
        if (CollectionUtil.isEmpty(records)) {
            return new PageUtils(new ArrayList(0), (int) iPage.getTotal(), (int) iPage.getSize(), (int) iPage.getCurrent());
        }
        List<UserListVo> vos = new LinkedList<>();
        List<String> userIdList = records.stream().map(UserListDto::getShopUserId).collect(Collectors.toList());
        //??????????????????
        Map<String, List<UserTagVo>> userTag = MapUtil.newHashMap(userIdList.size());
        List<MiniAccountTagGroup> tagGroups = miniAccountTagGroupService.getByUserListId(userIdList);
        if (CollectionUtil.isNotEmpty(tagGroups)) {
            setAccountGroupTag(userTag, tagGroups);
        }

        //??????????????????
        setPcAccountListVos(records, vos, userTag);
        return new PageUtils(vos, (int) iPage.getTotal(), (int) iPage.getSize(), (int) iPage.getCurrent());
    }

    /**
     * @param records ????????????
     * @param vos     ????????????
     * @param userTag ????????????
     */
    private void setPcAccountListVos(List<UserListDto> records, List<UserListVo> vos, Map<String, List<UserTagVo>> userTag) {
        for (UserListDto record : records) {
            UserListVo vo = new UserListVo();
            BeanUtils.copyProperties(record, vo);
            //Todo ???????????????????????????????????????????????????,???????????????+????????????  ????????????????????????
            vo.setSupplyBonus(record.getSupplyBonus());
            vo.setUserId(record.getShopUserId());
            List<UserTagVo> userTagVos = userTag.get(record.getShopUserId());
            vo.setUserTagVos(userTagVos);
            vo.setState(2);
            vos.add(vo);
        }
    }

    /**
     * ??????????????????
     *
     * @param userTag   ??????
     * @param tagGroups ??????????????????
     */
    private void setAccountGroupTag(Map<String, List<UserTagVo>> userTag, List<MiniAccountTagGroup> tagGroups) {
        List<Long> tagIdList = tagGroups.stream().map(MiniAccountTagGroup::getTagId).distinct().collect(Collectors.toList());
        List<MiniAccountTag> miniAccountTags = miniAccountTagService.getByIdList(tagIdList);
        Map<Long, MiniAccountTag> tagMap = miniAccountTags.stream().collect(Collectors.toMap(MiniAccountTag::getId, v -> v));
        tagGroups.stream().collect(Collectors.groupingBy(MiniAccountTagGroup::getUserId)).forEach((k, v) -> {
            List<UserTagVo> userTagVos = new ArrayList<>(v.size());
            for (MiniAccountTagGroup miniAccountTagGroup : v) {
                MiniAccountTag miniAccountTag = tagMap.get(miniAccountTagGroup.getTagId());
                if (miniAccountTag != null) {
                    UserTagVo userTagVo = new UserTagVo();
                    userTagVo.setTagId(miniAccountTag.getId());
                    userTagVo.setTagName(miniAccountTag.getTagName());
                    userTagVos.add(userTagVo);
                }
            }
            userTag.put(k, userTagVos);
        });
    }

    @Override
    public AccountInfoDto accountInfo(String shopUserId, List<Integer> infos) {
        AccountInfoDto accountInfoDto = new AccountInfoDto();

        for (Integer obj : infos) {
            switch (obj) {
                case 1:
                    MiniAccount miniAccount = this.getByShopUserId(shopUserId);
                    accountInfoDto.setMiniAccountunt(miniAccount);
                    break;
                case 2:
                    MiniAccountExtends miniAccountExtends = miniAccountExtendsService.findByShopUserId(shopUserId);
                    accountInfoDto.setMiniAccountExtends(miniAccountExtends);
                    List<Integer> restrictTypes = miniAccountRestrictService.getByUserId(shopUserId);
                    accountInfoDto.setRestrictTypes(restrictTypes);
                    break;
                case 3:
                    List<MiniAccountAddress> miniAccountAddresses = iMiniAccountAddressService.getUserAddress(shopUserId);
                    if (CollectionUtil.isNotEmpty(miniAccountAddresses)) {
                        //????????????,???????????????
                        CollectionUtil.sort(miniAccountAddresses, Comparator.comparing(MiniAccountAddress::getIsDefault).reversed());
                    }
                    accountInfoDto.setMiniAccountAddress(miniAccountAddresses);
                    break;
                case 4:
                    MiniAccountExtends ext = miniAccountExtendsService.findByShopUserId(shopUserId);
                    MiniAccountOauths miniAccountOauths = miniAccountOauthsService.getByUserId(OauthTypeEnum.WX_MINI.getType(), ext.getUserId());
                    accountInfoDto.setMiniAccountOauths(miniAccountOauths);
                    break;
                default:
            }
        }
        return accountInfoDto;
    }

    @Override
    public PageUtils<List<BlacklistUserVo>> blacklist(Integer page, Integer size, Integer permission, String fuzzy) {
        Map<String, Object> paramMap = new HashMap<>(8);
        paramMap.put("permission", permission);
        if (StrUtil.isNotEmpty(fuzzy)) {
            paramMap.put("fuzzy", "%" + fuzzy + "%");
        }
        IPage<BlacklistUserDto> userDtoIpage = this.baseMapper.selectByBlackListUser(new Page<BlacklistUserDto>(page, size), paramMap);
        List<BlacklistUserDto> records = userDtoIpage.getRecords();
        if (CollectionUtil.isEmpty(records)) {
            return new PageUtils(new ArrayList(0), (int) userDtoIpage.getTotal(), (int) userDtoIpage.getSize(), (int) userDtoIpage.getCurrent());
        }
        List<BlacklistUserVo> vos = new ArrayList<>(records.size());

        List<String> userIds = records.stream().map(BlacklistUserDto::getShopUserId).collect(Collectors.toList());
        Map<String, List<MiniAccountRestrict>> accountRestrictMap = miniAccountRestrictService.getByUserIds(userIds).stream().collect(Collectors.groupingBy(MiniAccountRestrict::getUserId));

        for (BlacklistUserDto record : records) {
            BlacklistUserVo vo = new BlacklistUserVo();
            BeanUtils.copyProperties(record, vo);
            vo.setUserId(record.getShopUserId());
            List<Integer> blacklistTypeList = accountRestrictMap.get(vo.getUserId()).stream().map(MiniAccountRestrict::getRestrictType).collect(Collectors.toList());
            vo.setBlacklistType(blacklistTypeList);
            vos.add(vo);
        }

        return new PageUtils(vos, (int) userDtoIpage.getTotal(), (int) userDtoIpage.getSize(), (int) userDtoIpage.getCurrent());
    }


    @Override
    public List<MiniAccountExtDto> accountsInfoList(List<String> shopUserIds) {
        return this.baseMapper.selectByShopUserIds(shopUserIds);
    }


    @Override
    public String qrCode() {
        CurUserDto httpCurUser = CurUserUtil.getHttpCurUser();
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("shopUserId", httpCurUser.getUserId());
        QrConfig qrConfig = QrConfig.create();
        BufferedImage generate = QrCodeUtil.generate(jsonObject.toJSONString(), qrConfig);
        byte[] bytes = QrCodeUtil.generatePng(jsonObject.toJSONString(), qrConfig);
        return Base64.encode(bytes);
    }


    @Override
    public MiniAccount getByShopUserId(String shopUserId) {
        return this.getBaseMapper().selectByShopUserId(shopUserId);
    }


}




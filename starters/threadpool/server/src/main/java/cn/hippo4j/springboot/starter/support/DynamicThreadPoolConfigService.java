/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.hippo4j.springboot.starter.support;

import cn.hippo4j.common.executor.ThreadPoolExecutorHolder;
import cn.hippo4j.common.executor.ThreadPoolExecutorRegistry;
import cn.hippo4j.common.model.Result;
import cn.hippo4j.common.model.ThreadPoolParameterInfo;
import cn.hippo4j.common.model.register.DynamicThreadPoolRegisterParameter;
import cn.hippo4j.common.model.register.DynamicThreadPoolRegisterWrapper;
import cn.hippo4j.common.toolkit.Assert;
import cn.hippo4j.common.toolkit.BooleanUtil;
import cn.hippo4j.common.toolkit.CollectionUtil;
import cn.hippo4j.common.toolkit.JSONUtil;
import cn.hippo4j.core.executor.support.service.AbstractDynamicThreadPoolService;
import cn.hippo4j.springboot.starter.config.BootstrapProperties;
import cn.hippo4j.springboot.starter.core.DynamicThreadPoolSubscribeConfig;
import cn.hippo4j.springboot.starter.notify.ServerModeNotifyConfigBuilder;
import cn.hippo4j.springboot.starter.remote.HttpAgent;
import cn.hippo4j.threadpool.message.api.NotifyConfigDTO;
import cn.hippo4j.threadpool.message.core.service.GlobalNotifyAlarmManage;
import cn.hippo4j.threadpool.message.core.service.ThreadPoolBaseSendMessageService;
import cn.hippo4j.threadpool.message.core.service.ThreadPoolNotifyAlarm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static cn.hippo4j.common.constant.Constants.REGISTER_DYNAMIC_THREAD_POOL_PATH;

/**
 * Dynamic thread-pool config service.
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicThreadPoolConfigService extends AbstractDynamicThreadPoolService {

    private final HttpAgent httpAgent;
    private final BootstrapProperties properties;
    private final ServerModeNotifyConfigBuilder serverModeNotifyConfigBuilder;
    private final ThreadPoolBaseSendMessageService threadPoolBaseSendMessageService;
    private final DynamicThreadPoolSubscribeConfig dynamicThreadPoolSubscribeConfig;

    @Override
    public ThreadPoolExecutor registerDynamicThreadPool(DynamicThreadPoolRegisterWrapper registerWrapper) {
        ThreadPoolExecutor dynamicThreadPoolExecutor = registerExecutor(registerWrapper);
        subscribeConfig(registerWrapper);
        putNotifyAlarmConfig(registerWrapper);
        return dynamicThreadPoolExecutor;
    }


    private ThreadPoolExecutor registerExecutor(DynamicThreadPoolRegisterWrapper registerWrapper) {
        DynamicThreadPoolRegisterParameter registerParameter = registerWrapper.getParameter();
        // 检查线程池参数
        checkThreadPoolParameter(registerParameter);

        String threadPoolId = registerParameter.getThreadPoolId();
        try {
            // TODO：方法名不合理
            failDynamicThreadPoolRegisterWrapper(registerWrapper);
            // 通过HTTP agent 注册线程池
            // registerWrapper 最终将被转化为 JSON 字符串
            Result registerResult = httpAgent.httpPost(REGISTER_DYNAMIC_THREAD_POOL_PATH, registerWrapper);
            if (registerResult == null || !registerResult.isSuccess()) {
                // TODO：注册时没有重试机制
                throw new RuntimeException("Dynamic thread pool registration returns error."
                        + Optional.ofNullable(registerResult).map(Result::getMessage).orElse(""));
            }
        } catch (Throwable ex) {
            log.error("Dynamic thread pool registration execution error: {}", threadPoolId, ex);
            throw ex;
        }
        // 通过json工具实现转化，效率低
        ThreadPoolParameterInfo parameter = JSONUtil.parseObject(JSONUtil.toJSONString(registerParameter), ThreadPoolParameterInfo.class);
        ThreadPoolExecutor dynamicThreadPoolExecutor = buildDynamicThreadPoolExecutor(registerParameter);
        ThreadPoolExecutorHolder executorHolder = new ThreadPoolExecutorHolder(threadPoolId, dynamicThreadPoolExecutor, null);
        executorHolder.setParameterInfo(parameter);
        ThreadPoolExecutorRegistry.putHolder(executorHolder);
        return dynamicThreadPoolExecutor;
    }

    private void subscribeConfig(DynamicThreadPoolRegisterWrapper registerWrapper) {
        dynamicThreadPoolSubscribeConfig.subscribeConfig(registerWrapper.getParameter().getThreadPoolId());
    }

    private void putNotifyAlarmConfig(DynamicThreadPoolRegisterWrapper registerWrapper) {
        DynamicThreadPoolRegisterParameter registerParameter = registerWrapper.getParameter();
        ThreadPoolNotifyAlarm threadPoolNotifyAlarm = new ThreadPoolNotifyAlarm(
                BooleanUtil.toBoolean(String.valueOf(registerParameter.getIsAlarm())),
                registerParameter.getActiveAlarm(),
                registerParameter.getCapacityAlarm());
        GlobalNotifyAlarmManage.put(registerParameter.getThreadPoolId(), threadPoolNotifyAlarm);
        Map<String, List<NotifyConfigDTO>> builderNotify = serverModeNotifyConfigBuilder.getAndInitNotify(CollectionUtil.newArrayList(registerParameter.getThreadPoolId()));
        threadPoolBaseSendMessageService.putPlatform(builderNotify);
    }

    private void checkThreadPoolParameter(DynamicThreadPoolRegisterParameter registerParameter) {
        //TODO: 此处应该先判断一下线程池id非空
        Assert.isTrue(!registerParameter.getThreadPoolId().contains("+"), "The thread pool contains sensitive characters.");
    }

    private void failDynamicThreadPoolRegisterWrapper(DynamicThreadPoolRegisterWrapper registerWrapper) {
        registerWrapper.setTenantId(properties.getNamespace());
        registerWrapper.setItemId(properties.getItemId());
    }
}

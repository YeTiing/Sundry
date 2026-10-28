package com.sundry.assistant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 助手接口已改成 DeferredResult：测试里需要先等 async 开始，再 dispatch。
 * 对同步失败（401/400/429）不触发 async，直接返回原始结果。
 */
final class AsyncAssistantCalls {

    private AsyncAssistantCalls() {
    }

    static ResultActions chat(MockMvc mockMvc, String token, String body) throws Exception {
        return perform(mockMvc, post("/api/assistant/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    static ResultActions anonymous(MockMvc mockMvc, String body) throws Exception {
        return perform(mockMvc, post("/api/assistant/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    static ResultActions perform(MockMvc mockMvc,
                                 MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return actions(result);
    }

    private static ResultActions actions(MvcResult result) {
        return new ResultActions() {
            @Override
            public ResultActions andExpect(ResultMatcher matcher) throws Exception {
                matcher.match(result);
                return this;
            }

            @Override
            public ResultActions andDo(ResultHandler handler) throws Exception {
                handler.handle(result);
                return this;
            }

            @Override
            public MvcResult andReturn() {
                return result;
            }
        };
    }
}

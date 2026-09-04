package com.nora.agent.service;

import com.nora.agent.config.LlmProperties;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.dto.CitationDto;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chat orchestration: retrieve knowledge chunks, inject them into the system
 * prompt, stream the LLM answer token by token (LangChain4j OpenAI-compatible
 * client pointed at the configured relay).
 */
@Service
public class ChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);

    /** System prompt base, per architecture-v2.md section 4.2. */
    private static final String SYSTEM_PROMPT = """
            你是 Nora 个人工作台的助手。回答必须：
            1. 优先使用检索到的知识库内容
            2. 引用来源时使用 [[docName]] 标记
            3. 如果知识库没有相关内容，如实说明并基于常识回答
            """;

    private final LlmProperties llmProperties;
    private final RagRetrievalClient ragRetrievalClient;

    public ChatOrchestrationService(LlmProperties llmProperties,
                                    RagRetrievalClient ragRetrievalClient) {
        this.llmProperties = llmProperties;
        this.ragRetrievalClient = ragRetrievalClient;
    }

    /**
     * Runs one chat turn: retrieval step + streaming answer.
     *
     * @param userMessage   the user's message text
     * @param history       prior turns of this session (oldest first), for multi-turn context
     * @param eventConsumer receives step/delta/sources events as they happen
     * @return future completed with the full answer text once the stream ends
     */
    public CompletableFuture<ChatTurn> chat(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            ChatEventConsumer eventConsumer) {
        // Step 1: knowledge retrieval (best-effort, happens before the LLM call)
        long retrievalStart = System.currentTimeMillis();
        List<CitationDto> citations = ragRetrievalClient.search(userMessage, 6);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        if (!citations.isEmpty()) {
            eventConsumer.step(new ChatStepDto(
                    "1", "tool", "检索知识库",
                    "召回 " + citations.size() + " 个相关片段（" + citations.get(0).docName() + " 等）",
                    retrievalMs, "completed"));
            eventConsumer.sources(citations);
        } else {
            eventConsumer.step(new ChatStepDto(
                    "1", "tool", "检索知识库", "知识库无相关片段", retrievalMs, "completed"));
        }

        // Step 2: think + stream the answer
        long answerStart = System.currentTimeMillis();
        eventConsumer.step(new ChatStepDto(
                "2", "think", "生成回答", null, null, "running"));

        List<ChatMessage> messages = buildMessages(userMessage, history, citations);

        CompletableFuture<ChatTurn> future = new CompletableFuture<>();
        StringBuilder answer = new StringBuilder();

        streamingModel().chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                answer.append(token);
                eventConsumer.delta(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                eventConsumer.step(new ChatStepDto(
                        "2", "think", "生成回答", null,
                        System.currentTimeMillis() - answerStart, "completed"));
                future.complete(new ChatTurn(answer.toString(), citations));
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM stream failed: {}", error.getMessage());
                eventConsumer.step(new ChatStepDto(
                        "2", "think", "生成回答", "模型调用失败：" + error.getMessage(),
                        System.currentTimeMillis() - answerStart, "failed"));
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** Whether an LLM API key is configured. */
    public boolean configured() {
        return llmProperties.configured();
    }

    private List<ChatMessage> buildMessages(String userMessage,
                                            List<ChatStoreService.StoredMessage> history,
                                            List<CitationDto> citations) {
        var messages = new java.util.ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(systemPromptWith(citations)));

        // Two-turn context window: prior user questions and assistant answers
        int from = Math.max(0, history.size() - 6);
        for (int i = from; i < history.size(); i++) {
            ChatStoreService.StoredMessage msg = history.get(i);
            if ("user".equals(msg.role())) {
                messages.add(UserMessage.from(msg.content()));
            } else if ("assistant".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                messages.add(AiMessage.from(msg.content()));
            }
        }
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    private String systemPromptWith(List<CitationDto> citations) {
        if (citations.isEmpty()) {
            return SYSTEM_PROMPT;
        }
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n以下是知识库检索到的相关片段：\n");
        for (int i = 0; i < citations.size(); i++) {
            CitationDto c = citations.get(i);
            sb.append("[[").append(c.docName()).append("#chunk").append(c.chunkIndex())
                    .append("]] ").append(c.snippet()).append('\n');
        }
        return sb.toString();
    }

    private StreamingChatModel streamingModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(llmProperties.baseUrl())
                .apiKey(llmProperties.apiKey())
                .modelName(llmProperties.model())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /** Callbacks for the SSE events of one chat turn. */
    public interface ChatEventConsumer {

        void step(ChatStepDto step);

        void delta(String token);

        void sources(List<CitationDto> citations);
    }

    /** Result of one chat turn. */
    public record ChatTurn(String answer, List<CitationDto> citations) {
    }
}

package com.ute.studentconsulting.controller;

import com.ute.studentconsulting.entity.*;
import com.ute.studentconsulting.exception.BadRequestException;
import com.ute.studentconsulting.model.PaginationModel;
import com.ute.studentconsulting.model.QuestionModel;
import com.ute.studentconsulting.payload.request.AnswerRequest;
import com.ute.studentconsulting.payload.response.ApiSuccessResponse;
import com.ute.studentconsulting.payload.response.SuccessResponse;
import com.ute.studentconsulting.service.*;
import com.ute.studentconsulting.util.AuthUtils;
import com.ute.studentconsulting.util.QuestionUtils;
import com.ute.studentconsulting.util.SortUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
@Slf4j
public class StaffController {
    private final AuthUtils authUtils;
    private final FieldService fieldService;
    private final SortUtils sortUtils;
    private final QuestionService questionService;
    private final QuestionUtils questionUtils;
    private final AnswerService answerService;
    private final DepartmentService departmentService;
    private final ForwardQuestionService forwardQuestionService;
    private final ConversationService conversationService;
    private final MessageService messageService;

    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(@RequestBody AnswerRequest request) {
        return handleSendMessage(request);
    }

    private ResponseEntity<?> handleSendMessage(AnswerRequest request) {
        var sender = authUtils.getCurrentUser();
        var question = questionService.findById(request.getQuestionId());
        question.setStatus(2);
        questionService.save(question);
        var receiver = question.getUser();
        var conversation = findOrCreateConversation(sender, receiver);
        var message = new Message(request.getContent(), new Date(), false, conversation, sender);
        messageService.save(message);
        return ResponseEntity.ok(new SuccessResponse("Gửi tin nhắn thành công"));
    }

    private Conversation findOrCreateConversation(User sender, User receiver) {
        var conversation = conversationService.findByStaffIsAndUserIs(sender, receiver);
        if (conversation == null) {
            return conversationService.save(new Conversation(false, false, sender, receiver));
        }
        return conversation;
    }

    @PatchMapping("/questions/{questionId}/departments/{departmentId}")
    public ResponseEntity<?> forwardQuestion(@PathVariable("questionId") String questionId,
                                             @PathVariable("departmentId") String departmentId) {
        return handleForwardQuestion(questionId, departmentId);
    }

    private ResponseEntity<?> handleForwardQuestion(String questionId, String departmentId) {
        var question = questionService.findById(questionId);
        var fromDepartment = question.getDepartment();
        var toDepartment = departmentService.findById(departmentId);
        var ids = toDepartment.getFields().stream().map(Field::getId).toList();
        if (!ids.contains(question.getField().getId())) {
            throw new BadRequestException("Phòng ban mới không hỗ trợ lĩnh vực này",
                    "Phòng ban nhận câu hỏi chuyển tiếp không hỗ trợ lĩnh vực này: %s".formatted(question.getField().getId()), 10037);
        }
        question.setDepartment(toDepartment);
        questionService.save(question);
        forwardQuestionService.save(new ForwardQuestion(fromDepartment, toDepartment, question));
        return ResponseEntity.ok(new SuccessResponse(true, "Chuyển tiếp câu hỏi thành công"));
    }

    @GetMapping("/questions/{id}")
    public ResponseEntity<?> getQuestionById(@PathVariable("id") String id) {
        return handleGetQuestionById(id);
    }

    private ResponseEntity<?> handleGetQuestionById(String id) {
        var question = questionService.findById(id);
        var simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        var response = new QuestionModel(question.getId(),
                question.getTitle(), question.getContent(),
                simpleDateFormat.format(question.getDate()),
                question.getUser().getId(), question.getUser().getName(),
                question.getUser().getEmail(), question.getDepartment().getName(),
                question.getField().getName());
        return ResponseEntity.ok(new ApiSuccessResponse<>(response));
    }

    @PostMapping("/answers")
    public ResponseEntity<?> answerQuestion(@RequestBody AnswerRequest request) {
        return handleAnswerQuestion(request);
    }

    private ResponseEntity<?> handleAnswerQuestion(AnswerRequest request) {
        validationAnswer(request);
        var staff = authUtils.getCurrentUser();
        var question = questionService.findById(request.getQuestionId());
        if (question.getAnswer() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new SuccessResponse("Câu hỏi đã được trả lời"));
        }
        var answer = new Answer(request.getContent(), new Date(), false, staff, question);
        if (staff.getRole().getName().equals(RoleName.ROLE_DEPARTMENT_HEAD)) {
            answer.setApproved(true);
            question.setStatus(2);
        } else {
            question.setStatus(1);
        }
        answerService.save(answer);
        questionService.save(question);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SuccessResponse("Trả lời câu hỏi thành công"));
    }

    @GetMapping("/questions/had-questions")
    public ResponseEntity<?> getCheckHadQuestion(@RequestParam(defaultValue = "all", name = "value") String value) {
        return handleGetCheckHadQuestion(value);
    }

    private ResponseEntity<?> handleGetCheckHadQuestion(String value) {
        boolean hadQuestions;
        if (value.equals("all")) {
            var staff = authUtils.getCurrentUser();
            var ids = staff.getFields().stream().map(Field::getId).toList();
            hadQuestions = questionService.existsByStatusIsAndFieldIdIn(0, ids);
        } else {
            var field = fieldService.findById(value);
            hadQuestions = questionService.existsByStatusIsAndFieldIs(0, field);
        }
        return ResponseEntity.ok(new ApiSuccessResponse<>(hadQuestions));
    }

    @GetMapping("/questions")
    public ResponseEntity<?> getQuestions(
            @RequestParam(defaultValue = "all", name = "value") String value,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "3") int size,
            @RequestParam(defaultValue = "date, asc", name = "sort") String[] sort) {
        return handleGetQuestions(value, page, size, sort);
    }

    private ResponseEntity<?> handleGetQuestions(String value, int page, int size, String[] sort) {
        var staff = authUtils.getCurrentUser();
        var orders = sortUtils.sortOrders(sort);
        var pageable = PageRequest.of(page, size, Sort.by(orders));
        Page<Question> questionPage;
        if (value.equals("all")) {
            var ids = staff.getFields().stream().map(Field::getId).toList();
            questionPage = questionService.findByStatusIsAndFieldIdIn(0, ids, pageable);
        } else {
            var field = fieldService.findById(value);
            questionPage = questionService.findByStatusIsAndFieldIs(0, field, pageable);
        }
        var questions = questionUtils.mapQuestionPageToQuestionModels(questionPage);
        var response = new PaginationModel<>(
                questions, questionPage.getNumber(),
                questionPage.getTotalPages());
        return ResponseEntity.ok(new ApiSuccessResponse<>(response));
    }

    @GetMapping("/fields")
    public ResponseEntity<?> getFieldsInMyDepartment() {
        return handleGetFieldsInMyDepartment();
    }

    private ResponseEntity<?> handleGetFieldsInMyDepartment() {
        var staff = authUtils.getCurrentUser();
        var ids = getIds(staff);
        var fields = fieldService.findAllByIdInAndStatusIs(ids, true);
        return ResponseEntity.ok(new ApiSuccessResponse<>(fields));
    }

    private List<String> getIds(User staff) {
        if (staff.getRole().getName().equals(RoleName.ROLE_DEPARTMENT_HEAD)) {
            var department = authUtils.getCurrentUser().getDepartment();
            return department.getFields().stream().map(Field::getId).toList();
        }
        return staff.getFields().stream().map(Field::getId).toList();
    }

    private void validationAnswer(AnswerRequest request) {
        var title = request.getContent().trim();
        if (title.isEmpty()) {
            throw new BadRequestException("Nội dung không thể để trống", "Nội dung bị trống", 10093);
        }
    }

}

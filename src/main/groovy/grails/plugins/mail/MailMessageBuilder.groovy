/*
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.mail

import com.sun.mail.smtp.SMTPMessage
import grails.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.InputStreamSource
import org.springframework.mail.MailMessage
import org.springframework.mail.MailSender
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMailMessage
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.util.Assert

import javax.mail.Message
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import java.util.concurrent.ExecutorService

/**
 * Provides a DSL style interface to mail message sending/generation.
 *
 * If the builder is constructed without a MailMessageContentRenderer, it is incapable
 * of rendering GSP views into message content.
 */
class MailMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(MailMessageBuilder.class)

    MailSender mailSender
    final MailMessageContentRenderer mailMessageContentRenderer

    final String defaultFrom
    final String defaultTo
    final String overrideAddress

    private MailMessage message
    private MimeMessageHelper helper
    private Locale locale

    private String textContent
    private String htmlContent
    private String envelopeFrom

    private int multipart = MimeMessageHelper.MULTIPART_MODE_NO
	private boolean async = false

    private List<Inline> inlines = []

    private static class Inline {
        String id
        String contentType
        InputStreamSource toAdd
    }

    MailMessageBuilder(MailSender mailSender, Config config, MailMessageContentRenderer mailMessageContentRenderer = null) {
        this.mailSender = mailSender
        this.mailMessageContentRenderer = mailMessageContentRenderer

        this.overrideAddress = config.getProperty('grails.mail.overrideAddress')
        this.defaultFrom = overrideAddress ?: config.getProperty('grails.mail.default.from')
        this.defaultTo = overrideAddress ?: config.getProperty('grails.mail.default.to')
    }

    private MailMessage getMessage() {
		mailSender = grails.util.Holders.grailsApplication.mainContext.getBean("mailSender")
        if (!message) {
            if (mimeCapable) {
                helper = new MimeMessageHelper(mailSender.createMimeMessage(), multipart)
                message = new MimeMailMessage(helper)
            } else {
                message = new SimpleMailMessage()
            }

            if (defaultFrom) {
                message.from = defaultFrom
            }

            if (defaultTo) {
                message.setTo(defaultTo)
            }
        }
        message
    }

    MailMessage sendMessage(ExecutorService executorService) {
		//MailGrailsPlugin.groovy removes and recreates the mailSender on config change
		//mailSender can be null if we don't do this after a config change
		mailSender = grails.util.Holders.grailsApplication.mainContext.getBean("mailSender")

        MailMessage message = finishMessage()

        if (log.traceEnabled) {
            log.trace("Sending mail ${getDescription(message)} ...")
        }

        def sendingMsg = message instanceof MimeMailMessage ? message.mimeMessage : message
        if(envelopeFrom) {
            if(!mimeCapable) {
                throw new GrailsMailException("You must use a JavaMailSender to set the envelopeFrom.")
            }

            sendingMsg = new SMTPMessage(sendingMsg)
            sendingMsg.envelopeFrom = envelopeFrom
        }
        
		if(async){
			executorService.execute({
				try{
					mailSender.send(sendingMsg)
				}catch(Throwable t){
					if(log.errorEnabled) log.error("Failed to send email", t)
				}
			} as Runnable)
		}else{
			mailSender.send(sendingMsg)
		}

        if (log.traceEnabled) {
            log.trace("Sent mail ${getDescription(message)}} ...")
        }

        message
    }

    void multipart(boolean multipart) {
        this.multipart = MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED
    }

    void async(boolean async) {
        this.async = async
    }

    void multipart(int multipartMode) {
        this.multipart = multipartMode
    }

    void headers(Map hdrs) {
        Assert.notEmpty(hdrs, "headers cannot be null")

        // The message must be of type MimeMailMessage to add headers.
        if (!mimeCapable) {
            throw new GrailsMailException("You must use a JavaMailSender to customise the headers.")
        }

        MailMessage msg = getMessage()
		if(msg instanceof MimeMailMessage){
	        MimeMessage mimeMessage = ((MimeMailMessage)msg).mimeMessageHelper.mimeMessage
	        hdrs.each { name, value ->
	            String nameString = name?.toString()
	            String valueString = value?.toString()
	
	            Assert.hasText(nameString, "header names cannot be null or empty")
	            Assert.hasText(valueString, "header value for '$nameString' cannot be null")
	
	            mimeMessage.setHeader(nameString, valueString)
	        }
		}else{
			throw new GrailsMailException("mail message builder is not mime capable so headers cannot be set")
		}
    }

    void to(Object[] args) {
        Assert.notEmpty(args, "to cannot be null or empty")
        Assert.noNullElements(args, "to cannot contain null elements")

        getMessage().setTo(toDestinationAddresses(args))
    }

    void to(List args) {
        Assert.notEmpty(args, "to cannot be null or empty")
        Assert.noNullElements(args.toArray(), "to cannot contain null elements")

        to(*args)
    }

    void bcc(Object[] args) {
        Assert.notEmpty(args, "bcc cannot be null or empty")
        Assert.noNullElements(args, "bcc cannot contain null elements")

        getMessage().setBcc(toDestinationAddresses(args))
    }

    void bcc(List args) {
        Assert.notEmpty(args, "bcc cannot be null or empty")
        Assert.noNullElements(args.toArray(), "bcc cannot contain null elements")

        bcc(*args)
    }

    void cc(Object[] args) {
        Assert.notEmpty(args, "cc cannot be null or empty")
        Assert.noNullElements(args, "cc cannot contain null elements")

        getMessage().setCc(toDestinationAddresses(args))
    }

    void cc(List args) {
        Assert.notEmpty(args, "cc cannot be null or empty")
        Assert.noNullElements(args.toArray(), "cc cannot contain null elements")

        cc(*args)
    }

    void replyTo(CharSequence replyTo) {
        Assert.hasText(replyTo, "replyTo cannot be null or 0 length")

        getMessage().replyTo = replyTo.toString()
    }

    void from(CharSequence from) {
        Assert.hasText(from, "from cannot be null or 0 length")

        getMessage().from = from.toString()
    }

    void envelopeFrom(CharSequence envFrom) {
        Assert.hasText(envFrom, "envelope from cannot be null or 0 length")
        
        envelopeFrom = envFrom.toString()
    }
    
    void title(CharSequence title) {
        Assert.notNull(title, "title cannot be null")

        subject(title)
    }

    void subject(CharSequence title) {
        Assert.notNull(title, "subject cannot be null")

        getMessage().subject = title.toString()
    }

    void body(CharSequence body) {
        Assert.notNull(body, "body cannot be null")

        text(body)
    }

    void body(Map params) {
        Assert.notEmpty(params, "body cannot be null or empty")

        MailMessageContentRender render = doRender(params)

        if (render.html) {
            html(render.out.toString()) // @todo Spring mail helper will not set correct mime type if we give it XHTML
        } else {
            text(render.out.toString())
        }
    }

    protected MailMessageContentRender doRender(Map params) {
        if (mailMessageContentRenderer == null) {
            throw new GrailsMailException("mail message builder was constructed without a message content render so cannot render views")
        }

        if (!params.view) {
            throw new GrailsMailException("no view specified")
        }

        mailMessageContentRenderer.render(new StringWriter(), params.view, params.model, locale, params.plugin)
    }

    void text(Map params) {
        Assert.notEmpty(params, "text cannot be null or empty")

        text(doRender(params).out.toString())
    }

    void text(CharSequence text) {
        Assert.notNull(text, "text cannot be null")

        textContent = text.toString()
    }

    void html(Map params) {
        Assert.notEmpty(params, "html cannot be null or empty")

        html(doRender(params).out.toString())
    }

    void html(CharSequence text) {
        Assert.notNull(text, "html cannot be null")
		mailSender = grails.util.Holders.grailsApplication.mainContext.getBean("mailSender")

        if (mimeCapable) {
            htmlContent = text.toString()
        } else {
            throw new GrailsMailException("mail sender is not mime capable, try configuring a JavaMailSender")
        }
    }

    void locale(String localeStr) {
        Assert.hasText(localeStr, "locale cannot be null or empty")

        locale(new Locale(*localeStr.split('_', 3)))
    }

    void locale(Locale locale) {
        Assert.notNull(locale, "locale cannot be null")

        this.locale = locale
    }

    /**
     * @deprecated use attach(String, String, byte[])
     */
    void attachBytes(String fileName, String contentType, byte[] bytes) {
        attach(fileName, contentType, bytes)
    }

    void attach(String fileName, String contentType, byte[] bytes) {
        attach(fileName, contentType, new ByteArrayResource(bytes))
    }

    void attach(File file) {
        attach(file.name, file)
    }

    void attach(String fileName, File file) {
        if (!mimeCapable) {
            throw new GrailsMailException("Message is not an instance of org.springframework.mail.javamail.MimeMessage, cannot attach bytes!")
        }

        attach(fileName, helper.fileTypeMap.getContentType(file), file)
    }

    void attach(String fileName, String contentType, File file) {
        if (!file.exists()) {
            throw new FileNotFoundException("cannot use $file as an attachment as it does not exist")
        }

        attach(fileName, contentType, new FileSystemResource(file))
    }

    void attach(String fileName, String contentType, InputStreamSource source) {
        doAdd(fileName, contentType, source, true)
    }

    void inline(String contentId, String contentType, byte[] bytes) {
        inline(contentId, contentType, new ByteArrayResource(bytes))
    }

    void inline(File file) {
        inline(file.name, file)
    }

    void inline(String fileName, File file) {
        if (!mimeCapable) {
            throw new GrailsMailException("Message is not an instance of org.springframework.mail.javamail.MimeMessage, cannot attach bytes!")
        }

        inline(fileName, helper.fileTypeMap.getContentType(file), file)
    }

    void inline(String contentId, String contentType, File file) {
        if (!file.exists()) {
            throw new FileNotFoundException("cannot use $file as an attachment as it does not exist")
        }

        inline(contentId, contentType, new FileSystemResource(file))
    }

    void inline(String contentId, String contentType, InputStreamSource source) {
        inlines << new Inline(id: contentId, contentType: contentType, toAdd: source)
    }

    protected doAdd(String id, String contentType, InputStreamSource toAdd, boolean isAttachment) {
        mailSender = grails.util.Holders.grailsApplication.mainContext.getBean("mailSender")

        if (!mimeCapable) {
            throw new GrailsMailException("Message is not an instance of org.springframework.mail.javamail.MimeMessage, cannot attach bytes!")
        }

        assert multipart, "message is not marked as 'multipart'; use 'multipart true' as the first line in your builder DSL"

		getMessage() //ensure that helper is initialized
        if (isAttachment) {
            helper.addAttachment(MimeUtility.encodeWord(id), toAdd, contentType)
        } else {
            helper.addInline(MimeUtility.encodeWord(id), toAdd, contentType)
        }
    }

    boolean isMimeCapable() {
        mailSender instanceof JavaMailSender
    }

    protected String[] toDestinationAddresses(addresses) {
        if (overrideAddress) {
            addresses = addresses.collect { overrideAddress }
        }

        addresses.collect { it?.toString() } as String[]
    }

    protected getDescription(SimpleMailMessage message) {
        "[${message.subject}] from [${message.from}] to ${message.to}"
    }

    protected getDescription(Message message) {
        "[${message.subject}] from [${message.from}] to ${message.getRecipients(Message.RecipientType.TO)*.toString()}"
    }

    protected getDescription(MimeMailMessage message) {
        getDescription(message.mimeMessage)
    }

    MailMessage finishMessage() {
        MailMessage message = getMessage()

        if (htmlContent) {
            if (textContent) {
                helper.setText(textContent, htmlContent)
            } else {
                helper.setText(htmlContent, true)
            }
        } else {
            if (!textContent) {
                throw new GrailsMailException("message has no content, use text(), html() or body() methods to set content")
            }

            message.text = textContent
        }

        inlines.each {
            doAdd(it.id, it.contentType, it.toAdd, false)
        }

        message.sentDate = new Date()
        message
    }
}

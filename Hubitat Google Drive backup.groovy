import groovy.json.JsonSlurper
//import groovy.json.JsonOutput

/**
 *
 *  Based on code from Google SDM Api 0.6.0 by David Kilgore
 *  https://github.com/dkilgore90/google-sdm-api
 *  Copyright 2020 David Kilgore. All Rights Reserved
 *
 *  This software is free for Private Use. You may use and modify the software without distributing it.
 *  If you make a fork, and add new code, then you should create a pull request to add value, there is no
 *  guarantee that your pull request will be merged.
 *
 *  You may not grant a sublicense to modify and distribute this software to third parties without permission
 *  from the copyright holder
 *  Software is provided without warranty and your use of it is at your own risk.
 *
 */

/***
TODO:
 1) Specify folder name
 2) On start, query Drive for folder name and store folderId to state
 3) Use folderId in to uploadDrive method 
***/

definition(
	name: 'Hubitat Google Drive backup',
	namespace: 'hyposphere.net',
	parent: "hyposphere.net:P's Utilities",
	author: 'Pete Miller',
	description: 'Automatically upload Hubitat Hub backup to Google Drive account',
	//importUrl: 'https://raw.githubusercontent.com/dkilgore90/google-sdm-api/master/sdm-api-app.groovy',
	category: 'Discovery',
	oauth: true,
	iconUrl: '',
	iconX2Url: '',
	iconX3Url: ''
)

preferences {
    page(name: 'mainPage')
    page(name: 'debugPage')
}

mappings {
    path("/events") {
        action: [
            POST: "postEvents"
        ]
    }
    path("/handleAuth") {
        action: [
            GET: "handleAuthRedirect"
        ]
    }
//    path("/img/:deviceId") {
//        action: [
//            GET: "getDashboardImg"
//        ]
//    }
}

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug "$msg"
    }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Setup", install: true, uninstall: true) {
		section() {
			input "isPaused", "bool", title: "Pause app", defaultValue: false
		}
		
        section {
            input 'credsJson', 'text', title: 'Google credentials.json', required: true, submitOnChange: false
        }
        getAuthLink()

		section{
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }
                
        getDebugLink()
    }
}

def debugPage() {
    dynamicPage(name:"debugPage", title: "Debug", install: false, uninstall: false) {
        section {
            paragraph "Debug buttons"
        }
        section {
            input 'getToken', 'button', title: 'Log Access Token', submitOnChange: true
        }
        section {
            input 'refreshToken', 'button', title: 'Force Token Refresh', submitOnChange: true
        }
		section("Test") {
    		input name: "btnTest", type: "button", title: "Test"
		}
    }
}

def getAuthLink() {
	if (credsJson && state?.accessToken) {
        section {
            href(
                name       : 'authHref',
                title      : 'Auth Link',
                url        : buildAuthUrl(),
                description: 'Click this link to authorize with your Google Drive account'
            )
        }
    } else {
        section {
            paragraph "Authorization link is hidden until the required credentials.json inputs are provided, and App installation is saved by clicking 'Done'"
        } 
    }
}

def buildAuthUrl() {
    def creds = getCredentials()	
	url = 'https://accounts.google.com/o/oauth2/v2/auth?' + 
			'redirect_uri=https://cloud.hubitat.com/oauth/stateredirect' +
			'&state=' + getHubUID() + '/apps/' + app.id + '/handleAuth?access_token=' + state.accessToken +
			'&access_type=offline&prompt=consent&client_id=' + creds?.client_id + 
			'&response_type=code&scope=https://www.googleapis.com/auth/drive.file'

	return url
}

def getDebugLink() {
    section{
        href(
            name       : 'debugHref',
            title      : 'Debug buttons',
            page       : 'debugPage',
            description: 'Access debug buttons (log current googleAccessToken, force googleAccessToken refresh)'
        )
    }
}

def getCredentials() {
    //def uri = 'https://' + location.hub.localIP + '/local/credentials.json'
    //def creds = httpGet([uri: uri]) { response }
    def creds = new JsonSlurper().parseText(credsJson)
    return creds.web
}

def handleAuthRedirect() {
	log.info('successful redirect from google')
    unschedule('refreshLogin')
    def authCode = params.code
    login(authCode)
    runEvery1Hour('refreshLogin')
    def builder = new StringBuilder()
    builder << "<!DOCTYPE html><html><head><title>Hubitat Elevation - Google Drive</title></head>"
    builder << "<body><p>Congratulations! Google Drive has authenticated successfully</p>"
    builder << "<p><a href=https://${location.hub.localIP}/installedapp/configure/${app.id}/mainPage>Click here</a> to return to the App main page.</p></body></html>"
    
    def html = builder.toString()

    render contentType: "text/html", data: html, status: 200
}

def mainPageLink() {
    section {
        href(
            name       : 'Main page',
            page       : 'mainPage',
            description: 'Back to main page'
        )
    }
}

def updated() {
	if (isPaused == false) {
		log.info 'Hubitat Google Drive Backup updating'
		rescheduleLogin()
		//runEvery10Minutes('checkGoogle')
		//schedule('0 0 23 ? * *', 'driveRetentionJob')
		schedule('0 0 9 ? * *', 'downloadLatestBackup')
		subscribe(location, 'systemStart', 'initialize')
	} else {
		log.info 'Hubitat Google Drive Backup paused: Unscheduling'
    	unschedule()
    	unsubscribe()
	}
}

def installed() {
    log.info 'Hubitat Google Drive Backup installed'
    //initialize()
    createAccessToken()
    //runEvery10Minutes checkGoogle
    //schedule('0 0 23 ? * *', 'driveRetentionJob')
    subscribe(location, 'systemStart', 'initialize')
}

def uninstalled() {
    log.info 'Hubitat Google Drive Backup uninstalling'
    unschedule()
    unsubscribe()
}

def initialize(evt) {
    log.debug(evt)
    recover()
}

def recover() {
    rescheduleLogin()
    refreshAll()
}

def rescheduleLogin() {
    unschedule('refreshLogin')
    if (state?.googleRefreshToken) {
        refreshLogin()
        runEvery1Hour('refreshLogin')
    }
}

def login(String authCode) {
    log.info('Getting access_token from Google')
    def creds = getCredentials()
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
                    client_id    : creds.client_id,
                    client_secret: creds.client_secret,
                    code         : authCode,
                    grant_type   : 'authorization_code',
                    redirect_uri : 'https://cloud.hubitat.com/oauth/stateredirect'
                ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def refreshLogin() {
    logDebug('Refreshing access_token from Google')
    def creds = getCredentials()
    def uri = 'https://www.googleapis.com/oauth2/v4/token'
    def query = [
                    client_id    : creds.client_id,
                    client_secret: creds.client_secret,
                    refresh_token: state.googleRefreshToken,
                    grant_type   : 'refresh_token',
                ]
    def params = [uri: uri, query: query]
    try {
        httpPost(params) { response -> handleLoginResponse(response) }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error("Login refresh failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
}

def handleLoginResponse(resp) {
    def respCode = resp.getStatus()
    def respJson = resp.getData()
    logDebug("Authorized scopes: ${respJson.scope}")
    if (respJson.refresh_token) {
        state.googleRefreshToken = respJson.refresh_token
    }
    state.googleAccessToken = respJson.access_token
}

def handleBackoffRetryGet(map) {
    asynchttpGet(map.callback, map.data.params, map.data)
}

def putResponse(resp, data) {
    def respCode = resp.getStatus()
    if (respCode == 409) {
        log.warn('createEventSubscription returned status code 409 -- subscription already exists')
    } else if (respCode != 200) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        log.error("createEventSubscription returned status code ${respCode} -- ${respError}")
    } else {
        logDebug(resp.getJson())
        state.eventSubscription = 'v2'
    }
    if (respCode == 401 && !data.isRetry) {
        log.warn('Authorization token expired, will refresh and retry.')
        rescheduleLogin()
        data.isRetry = true
        asynchttpPut(putResponse, data.params, data)
    }
}

def logToken() {
    log.debug("Access Token: ${state.googleAccessToken}")
}

/*def refreshAll() {
    log.info('Dropping stale events with timestamp < now, and refreshing devices')
    state.lastRecovery = now()
    def children = getChildDevices()
    children.each {
        if (it != null) {
            getDeviceData(it)
        }
    }
} */

void testButton() {
	downloadLatestBackup()
	//if (! state.folderId) createFolder()
	//createFile(file, name)
}

String getDownloadFilename(resp) {
	def contentDisposition = resp.getHeaders()["Content-Disposition"]
	
	// Content-Disposition field:
	//    attachment; filename=Home_2021-07-05~2.2.7.128.lzf
	logDebug "get name 1: ${contentDisposition}"
	logDebug "get name 2: ${(contentDisposition =~ /filename=(.+)/)}"
	logDebug "get name 3: ${(contentDisposition =~ /filename=(.+)/)[ 0 ]}"
	logDebug "get name 4: ${(contentDisposition =~ /filename=(.+)/)[ 0 ][ 1 ]}"
	return (contentDisposition =~ /filename=(.+)/)[ 0 ][ 1 ]
}

void downloadLatestBackup() {
	String uri = 'http://' + location.hub.localIP + ':8080/hub/backupDB?fileName=latest'
	//String uri = 'http://' + location.hub.localIP + ':8080/local/backup_test.file'
	
	Map params = [
	    uri: uri,
		requestContentType: 'application/octet-stream',
		contentType: 'application/octet-stream',
		timeout: 30,
		ignoreSSLIssues: true
	]

	try {
		asynchttpGet('handleDownloadLatestBackup', params, [data: null])
	} catch (e) {
		if (debugMode) log.debug "There was an error: $e"	
	}
}

void handleDownloadLatestBackup(hubitat.scheduling.AsyncResponse resp, Map data) {
	String name = ""
	
	if (resp.getStatus() != 200 ) {
		log.debug "HTTP error: " + resp.getStatus()

		return
	}

	name = getDownloadFilename(resp)
	logDebug "Downloaded file (name: ${name})"
	//log.debug "HTTP download data (raw): " + resp.getData()
	//log.debug "HTTP download data (decoded): " + resp.decodeBase64()

	backupFile = resp.getData()
	
	if (! state.folderId) createFolder()
	createFile(backupFile, name)
}

void createFile(file, name) {
    String uri = 'https://www.googleapis.com/drive/v3/files'
    def headers = [ Authorization: "Bearer ${state.googleAccessToken}" ]
    def contentType = 'application/json'
    def ts = now()
    def body = [
        mimeType: 'application/octet-stream',
        name: name,
        parents: [ state.folderId ]
    ]
    def params = [ uri: uri, headers: headers, contentType: contentType, body: body ]
    logDebug("Creating Google Drive file for backup")
    asynchttpPost(handleCreateFile, params, [params: params, name: name, file: file])
}

def handleCreateFile(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPost(handleCreateFile, data.params, data)
        } else if (respCode == 404) {
            //log.warn("Known folder id not found -- resetting. A new folder will be created automatically.")
            log.warn("Known folder id not found")
        //} else if (respCode == 429 && data.backoffCount < 5) {
            //log.warn("Hit rate limit, backoff and retry -- response: ${respError}")
            //data.backoffCount = (data.backoffCount ?: 0) + 1
            //runIn(10, handleBackoffRetryPost, [overwrite: false, data: [callback: handleDeviceGet, data: data]])
        } else {
            log.error("Create file -- response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
		logDebug("Uploading Google Drive file")
        uploadDrive(respJson.id, data.file, data.name)
    }
}

def uploadDrive(id, file, name) {
    def uri = "https://www.googleapis.com/upload/drive/v3/files/${id}"
    def headers = [ Authorization: "Bearer ${state.googleAccessToken}" ]
    def query = [ uploadType: 'media' ]
    def contentType = 'application/octet-stream'
    def body = file.decodeBase64()
    def params = [ uri: uri, headers: headers, contentType: contentType, body: body ]
    logDebug("Uploading backup file to Google Drive")
    asynchttpPatch(handleUploadDrive, params, [name: name, params: params, fileId: id])
}

def handleUploadDrive(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPut(handleUploadDrive, data.params, data)
        //} else if (respCode == 429 && data.backoffCount < 5) {
            //log.warn("Hit rate limit, backoff and retry -- response: ${respError}")
            //data.backoffCount = (data.backoffCount ?: 0) + 1
            //runIn(10, handleBackoffRetryPost, [overwrite: false, data: [callback: handleDeviceGet, data: data]])
        } else {
            log.error("Upload data to file -- response code: ${respCode}, body: ${respError}")
        }
    } else {
		logDebug("Getting file info for new file")
		getAppDataDrive(data.fileId)
    }
}

def getAppDataDrive(fileId) {
    def uri = "https://www.googleapis.com/drive/v3/files/${fileId}"
    def headers = [ Authorization: 'Bearer ' + state.googleAccessToken ]
    def contentType = 'application/json'
    def query = [ fields: 'webContentLink']
    def params = [ uri: uri, headers: headers, contentType: contentType, query: query ]
    logDebug("Retrieving file by id to get file url")
    asynchttpGet(handleGetAppDataDrive, params, [params: params])
}

def handleGetAppDataDrive(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpGet(handlePhotoGet, data.params, data)
        } else {
            log.warn("file-get response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
		logDebug "Drive URL for new file: ${respJson.webContentLink}"
        //sendEvent(data.device, [name: 'image', value: '<img src="' + "${respJson.webContentLink}" + '" />', isStateChange: true])
    }
}

def createFolder() {
    def uri = 'https://www.googleapis.com/drive/v3/files'
    def headers = [ Authorization: "Bearer ${state.googleAccessToken}" ]
    def contentType = 'application/json'
    def body = [
        mimeType: 'application/vnd.google-apps.folder',
		parents: 'root',
        name: 'Hubitat Backups' // folder name
    ]
    def params = [ uri: uri, headers: headers, contentType: contentType, body: body ]
    log.info("Creating Google Drive folder")
    asynchttpPost(handleCreateFolder, params, [params: params])
}

def handleCreateFolder(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPost(handleCreateFolder, data.params, data)
        //} else if (respCode == 429 && data.backoffCount < 5) {
            //log.warn("Hit rate limit, backoff and retry -- response: ${respError}")
            //data.backoffCount = (data.backoffCount ?: 0) + 1
            //runIn(10, handleBackoffRetryPost, [overwrite: false, data: [callback: handleDeviceGet, data: data]])
        } else {
            log.error("Create folder -- response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
		log.debug "folder id returned: ${respJson.id}"
        state.folderId = (respJson.id)
        setFolderPermissions(respJson.id)
    }
}

def setFolderPermissions(folderId) {
    def uri = "https://www.googleapis.com/drive/v3/files/${folderId}/permissions"
    def headers = [ Authorization: "Bearer ${state.googleAccessToken}" ]
    def contentType = 'application/json'
    def body = [
        role: 'reader',
        type: 'anyone',
        allowFileDiscovery: false
    ]
    def params = [ uri: uri, headers: headers, contentType: contentType, body: body ]
    log.info("Setting Google Drive folder permissions")
    asynchttpPost(handleSetPermissions, params, [params: params])
}

def handleSetPermissions(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPost(handleSetPermissions, data.params, data)
        //} else if (respCode == 429 && data.backoffCount < 5) {
            //log.warn("Hit rate limit, backoff and retry -- response: ${respError}")
            //data.backoffCount = (data.backoffCount ?: 0) + 1
            //runIn(10, handleBackoffRetryPost, [overwrite: false, data: [callback: handleDeviceGet, data: data]])
        } else {
            log.error("Set permissions -- response code: ${respCode}, body: ${respError}")
        }
    }
}

/*
def getFilesToDelete(device) {
    def retentionDate = new Date(now() - (1000 * 3600 * 24 * retentionDays)).format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", TimeZone.getTimeZone("UTC"))
    def fullDevice = getChildDevice(device.getDeviceNetworkId())
    def folderId = fullDevice.getFolderId()
    def uri = 'https://www.googleapis.com/drive/v3/files'
    def headers = [ Authorization: "Bearer ${state.googleAccessToken}" ]
    def contentType = 'application/json'
    def query = [ q: "modifiedTime < '${retentionDate}' and '${folderId}' in parents" ]
    def params = [ uri: uri, headers: headers, contentType: contentType, query: query ]
    log.info("Retrieving files to delete for device: ${device}, based on retentionDays: ${retentionDays}")
    logDebug(params)
    asynchttpGet(handleGetFilesToDelete, params, [device: device, params: params])
}

def handleGetFilesToDelete(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpGet(handleGetFilesToDelete, data.params, data)
        //} else if (respCode == 429 && data.backoffCount < 5) {
            //log.warn("Hit rate limit, backoff and retry -- response: ${respError}")
            //data.backoffCount = (data.backoffCount ?: 0) + 1
            //runIn(10, handleBackoffRetryPost, [overwrite: false, data: [callback: handleDeviceGet, data: data]])
        } else {
            log.error("Files to delete retrieval -- response code: ${respCode}, body: ${respError}")
        }
    } else {
        def respJson = resp.getJson()
        def nextPage = respJson.nextPageToken ? true : false
        def idList = []
        respJson.files.each {
            idList.add(it.id)
        }
        if (idList) {
            deleteFilesBatch(data.device, idList, nextPage)
        } else {
            log.info("No files found to delete -- device: ${data.device}")
        }
    }
}

def deleteFilesBatch(device, idList, nextPage) {
    def uri = 'https://www.googleapis.com/batch/drive/v3'
    def headers = [
        Authorization: "Bearer ${state.googleAccessToken}",
        'Content-Type': 'multipart/mixed; boundary=END_OF_PART'
    ]
    def requestContentType = 'text/plain'
    def builder = new StringBuilder()
    idList.each {
        builder << '--END_OF_PART\r\n'
        builder << 'Content-type: application/http\r\n\r\n'
        builder << "DELETE https://www.googleapis.com/drive/v3/files/${it}\r\n\r\n"
    }
    builder << '--END_OF_PART--'
    def body = builder.toString()
    def params = [ uri: uri, headers: headers, body: body, requestContentType: requestContentType ]
    log.info("Sending batched file delete request -- count: ${idList.size()} -- for device: ${device}")
    logDebug(body)
    asynchttpPost(handleDeleteFilesBatch, params, [device: device, params: params, nextPage: nextPage])
}

def handleDeleteFilesBatch(resp, data) {
    def respCode = resp.getStatus()
    if (resp.hasError()) {
        def respError = ''
        try {
            respError = resp.getErrorData().replaceAll('[\n]', '').replaceAll('[ \t]+', ' ')
        } catch (Exception ignored) {
            // no response body
        }
        // batch error at top-level is unexpected at any time -- log for further analysis
        log.error("Batch delete -- response code: ${respCode}, body: ${respError}")
    } else {
        def respData = new String(resp.getData().decodeBase64())
        logDebug(respData)
        def unauthorized = respData =~ /HTTP\/1.1 401/
        if (unauthorized && !data.isRetry) {
            log.warn('Authorization token expired, will refresh and retry.')
            rescheduleLogin()
            data.isRetry = true
            asynchttpPost(handleDeleteFilesBatch, data.params, data)
        }
        if (data.nextPage) {
            log.info("Additional pages of files to delete for device: ${data.device} -- will run query sequence again")
            getFilesToDelete(data.device)
        }
    }
}

def driveRetentionJob() {
	log.info('Running Google Drive retention cleanup job')
	def children = getChildDevices()
	children.each {
		if (it.hasCapability('ImageCapture')) {
			getFilesToDelete(it)
		}
	}
}
*/

void appButtonHandler(String btn) {
	switch (btn) {
	case "btnTest":
		testButton()
		break
	default:
		log.warn "Unhandled button press: $btn"
	}
}

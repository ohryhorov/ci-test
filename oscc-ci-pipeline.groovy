/**
 *
 *
 *
 **/
common = new com.mirantis.mk.Common()

def restCall(master, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${master.url}${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    if (master.authToken) {
        // XXX: removeme
        connection.setRequestProperty('X-Auth-Token', master.authToken)
    }

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        connection.setRequestProperty('Content-Type', 'application/json')                    
        
        def out = new OutputStreamWriter(connection.outputStream)
        out.write(data)
        out.close()
    }

    if ( connection.responseCode >= 200 && connection.responseCode < 300 ) {
        res = connection.inputStream.text
        try {
            return new groovy.json.JsonSlurperClassic().parseText(res)
        } catch (Exception e) {
            return res
        }
    } else {
        throw new Exception(connection.responseCode + ": " + connection.inputStream.text)
    }
}

def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}

def restGet(master, uri, data = null) {
    return restCall(master, uri, 'GET', data)
}

def listPublish(server) {

    return new groovy.json.JsonSlurperClassic().parseText(restGet(server, '/api/publish'))

}

def snapshotCreate(server, repo) {
    def now = new Date();
    def ts = now.format("yyyyMMddHHmmss", TimeZone.getTimeZone('UTC'));
    def snapshot = "${repo}-${ts}-oscc-dev"

    String data = "{\"Name\": \"${snapshot}\"}"
    
    resp = restPost(server, "/api/repos/${repo}/snapshots", data) 
    echo "response: ${resp}"

//    try {
//        sh(script: "curl -f -X POST -H 'Content-Type: application/json' --data '{\"Name\":\"$snapshot\"}' ${server}/api/repos/${repo}/snapshots", returnStdout: true, )
//    } catch (err) {
//        echo (err)
//    }

    return snapshot
}

def snapshotPublish(server, snapshot, distribution, components, prefixes = []) {

    String data = "{\"SourceKind\": \"snapshot\", \"Sources\": [{\"Name\": \"${snapshot}\", \"Component\": \"${components}\" }], \"Architectures\": [\"amd64\"], \"Distribution\": \"${distribution}\"}"

//        sh(script: "curl -X POST -H 'Content-Type: application/json' --data '{\"SourceKind\": \"snapshot\", \"Sources\": [{\"Name\": \"${snapshot}\", 
//            \"Component\": \"${components}\"}], \"Architectures\": [\"amd64\"], \"Distribution\": \"${distribution}\"}' ${server}/api/publish/${prefix}", returnStdout: true, )

    return restPost(server, "/api/publish/${prefix}", data)

}

def snapshotUnpublish(server, snapshot, distribution, components, prefixes = []) {

    for (prefix in prefixes) {
        sh(script: "curl -X DELETE http://172.16.48.254:8084/api/publish/${prefix}/${distribution}", returnStdout: true, )
    }

}

node('python'){
    def server = [
        'url': "http://172.18.162.193:8080"
    ]
    def repo = "ubuntu-xenial-salt"
    def distribution = "dev-os-salt-formulas"
    def components = "dev-salt-formulas"
    def prefixes = ["oscc-dev", "s3:aptcdn:oscc-dev"]
    def tmp_repo_node_name = "apt.mirantis.com"
    def deployBuild
    def STACK_RECLASS_ADDRESS = 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-aio'
    def OPENSTACK_RELEASES = 'ocata,pike'
    def buildResult = [:]
    def notToPromote   

    stage("Creating snapshot from nightly repo"){
        snapshot = snapshotCreate(server, repo)
        common.successMsg("Snapshot: ${snapshot} has been created")
    }

    stage("Publishing the snapshots"){

        listPublish(server)

/*        for (prefix in prefixes) {
            echo (snapshotPublish(server, snapshot, distribution, components, prefix))
            common.successMsg("Snapshot ${snapshot} has been published for prefix ${prefix}")
        } */
    }
   
    stage("Deploying environment and testing"){
/*        for (openstack_release in OPENSTACK_RELEASES.tokenize(',')) {
            deployBuild = build(job: 'oscore-MCP1.1-virtual_mcp11_aio-pike-stable', propagate: false, parameters: [
                [$class: 'StringParameterValue', name: 'EXTRA_REPO', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components}"],
                [$class: 'StringParameterValue', name: 'EXTRA_REPO_PRIORITY', value: "1200"],
                [$class: 'StringParameterValue', name: 'EXTRA_REPO_PIN', value: "origin ${tmp_repo_node_name}"],
                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
                [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/" + openstack_release.replaceAll(' ','')],
            ]) 
            buildResult[openstack_release.replaceAll(' ','')] = deployBuild.result
        } */
    }

    stage("Managing deployment results") {
        notToPromote = buildResult.find {openstack_release, result -> result != 'SUCCESS'}

        buildResult.each {openstack_release, result -> 
            println("${openstack_release}: ${result}")
        }
    }

    stage("Promotion to testing repo"){
        if (notToPromote) {
            echo "Snapshot can't be promoted!!!"
        }
    }
}

/**
 * DEPLOY_JOB_NAME
 * DISTRIBUTION
 * COMPONENTS
 * PREFIXES
 * TMP_REPO_NODE_NAME
 * STACK_RECLASS_ADDRESS
 * OPENSTACK_RELEASES
 * SOURCE_REPO_NAME
 * APTLY_API_URL
 **/
common = new com.mirantis.mk.Common()

/**
 * Had to override REST functions from pipeline-library here due to 'slashy-string issue'. The issue
 * appears during object type conversion to string by toString() method and an each quote is escaped by slash.
 * However even if sent data was defined as String explicitely then restCall function doesn't set request
 * property 'Content-Type' to 'application/json' and request are sent with webform header which is not acceptable
 * fot aptly api.
 **/

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
        throw (connection.responseCode + ': ' + connection.inputStream.text)
    }
}

def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}

def restGet(master, uri, data = null) {
    return restCall(master, uri, 'GET', data)
}

def restDel(master, uri, data = null) {
    return restCall(master, uri, 'DELETE', data)
}

def matchPublished(server, distribution, prefix) {
    def list_published = restGet(server, '/api/publish')
    def storage

    for (items in list_published) {
        for (row in items) {
            println ("items: ${items} key ${row.key} value ${row.value}")
            if (prefix.tokenize(':')[1]) {
                storage = prefix.tokenize(':')[0] + ':' + prefix.tokenize(':')[1]
                println ("storage: ${storage}")

                if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == storage) {
                    println ("items1: ${items} key ${row.key} value ${row.value}")
                    return prefix
                }
            } else {
                if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == '') {
                    println ("items2: ${items} key ${row.key} value ${row.value}")
                    return prefix
                }
            }
        }
    }

    return false
}

def getnightlySnapshot(server, distribution, prefix, component) {
    def list_published = restGet(server, '/api/publish')
    def storage

    for (items in list_published) {
        for (row in items) {
            println ("items: ${items} key ${row.key} value ${row.value}")
            if (prefix.tokenize(':')[1]) {
                storage = prefix.tokenize(':')[0] + ':' + prefix.tokenize(':')[1]
//                println ("storage: ${storage}")

                if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == storage) {
                    println ("items1: ${items} key ${row.key} value ${row.value}")
                    for (source in items['Sources']){
                        if (source['Component'] == component) {
                            println ("X2: " + source['Name'])
                            return source['Name']
                        }
                    }
                }
            } else {
                if (row.key == 'Distribution' && row.value == distribution && items['Prefix'] == prefix.tokenize(':').last() && items['Storage'] == '') {
                    println ("items2: ${items} key ${row.key} value ${row.value} sources " + items['Sources'])
                    for (source in items['Sources']){
                        if (source['Component'] == component) {
                            println ("X3: " + source['Name'])
                            return source['Name']
                        }
                    }
                }
            }
        }
    }

    return false
}

def snapshotPackages(server, snapshot, packages_list) {
    def pkgs = restGet(server, "/api/snapshots/${snapshot}/packages")

    println ("PKGS: ${pkgs}")

    for (package_pattern in packages_list.tokenize(',')) {
        println ("PKG1: ${package_pattern}")
//        println ("PKGS: ${pkgs}")
    }

    return pkgs

}

def snapshotCreate(server, repo) {
    def now = new Date()
    def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
    def snapshot = "${repo}-${ts}-oscc-dev"

    String data = "{\"Name\": \"${snapshot}\"}"

    resp = restPost(server, "/api/repos/${repo}/snapshots", data)
    echo "response: ${resp}"

    return snapshot
}

def snapshotPublish(server, distribution, components, prefix) {
//def snapshotPublish(server, snapshot, distribution, components, prefix) {
    def aptly = new com.mirantis.mk.Aptly()

//    String data = "{\"SourceKind\": \"snapshot\", \"Sources\": [{\"Name\": \"${snapshot}\", \"Component\": \"${components}\" }], \"Architectures\": [\"amd64\"], \"Distribution\": \"${distribution}\"}"

    aptly.promotePublish(server['url'], 'xenial/nightly', "${prefix}/${distribution}", 'false', components, '', '', '-d --timeout 1200', '', '')

//    return restPost(server, "/api/publish/${prefix}", data)

}

def snapshotUnpublish(server, prefix, distribution) {

    return restDel(server, "/api/publish/${prefix}/${distribution}")

}

node('python'){
    def server = [
        'url': 'http://172.16.48.254:8084',
    ]
//    def repo = 'ubuntu-xenial-salt'
    def DISTRIBUTION = 'dev-os-salt-formulas'
    def components = 'salt'
//    def prefixes = ['oscc-dev', 's3:aptcdn:oscc-dev']
    def prefixes = ['oscc-dev']
    def tmp_repo_node_name = 'apt.mcp.mirantis.net:8085'
//    def deployBuild
    def STACK_RECLASS_ADDRESS = 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-aio'
    def OPENSTACK_RELEASES = 'ocata,pike'
    def OPENSTACK_COMPONENTS_LIST = 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
//    def buildResult = [:]
    def notToPromote
    def DEPLOY_JOB_NAME = 'oscore-MCP1.1-test-release-nightly'
//    def DEPLOY_JOB_NAME = 'oscore-MCP1.1-virtual_mcp11_aio-pike-stable'
    def testBuilds = [:]
    def deploy_release = [:]
    def distribution

    lock('aptly-api') {

        stage('Creating snapshot from nightly repo'){
//            snapshot = snapshotCreate(server, repo)
            snapshot = 'ubuntu-xenial-salt-20171219081745-oscc-dev'
            common.successMsg("Snapshot ${snapshot} has been created")
        }

        stage('Publishing the snapshots'){
            def now = new Date()
            def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
            distribution = "${DISTRIBUTION}-${ts}"

            def nightlySnapshot = getnightlySnapshot(server, 'nightly', 'xenial', components)

            snapshotPackages(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)

            for (prefix in prefixes) {
/*                common.infoMsg("Checking ${distribution} is published for prefix ${prefix}")
                retPrefix = matchPublished(server, distribution, prefix)

                if (retPrefix) {
                    echo "Can't be published for prefix ${retPrefix}. The distribution will be unpublished."
                    snapshotUnpublish(server, retPrefix, distribution)
                    common.successMsg("Distribution ${distribution} has been unpublished for prefix ${retPrefix}")
                }
*/
                common.infoMsg("Publishing ${distribution} for prefix ${prefix} is started.")
//                snapshotPublish(server, snapshot, distribution, components, prefix)
//                snapshotPublish(server, distribution, components, prefix)
                common.successMsg("Snapshot ${snapshot} has been published for prefix ${prefix}")
            }
        }
    }

/*    stage('Deploying environment and testing'){
        for (openstack_release in OPENSTACK_RELEASES.tokenize(',')) {
            def release = openstack_release
            deploy_release["OpenStack ${release} deployment"] = {
                node('oscore-testing') {
                    testBuilds["${release}"] = build job: DEPLOY_JOB_NAME, propagate: false, parameters: [
                        [$class: 'StringParameterValue', name: 'EXTRA_REPO', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components}"],
                        [$class: 'StringParameterValue', name: 'EXTRA_REPO_PRIORITY', value: '1300'],
                        [$class: 'StringParameterValue', name: 'EXTRA_REPO_PIN', value: "release c=${components}"],
                        [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
                        [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${release}"],
                    ]
                }
            }
        }
    } */

    stage('Running parallel OpenStack deployment') {
        parallel deploy_release
    }

    stage('Managing deployment results') {
        for (k in testBuilds.keySet()) {
            if (testBuilds[k].result != 'SUCCESS') {
                notToPromote = true
            }
            println(k + ': ' + testBuilds[k].result)
        }

//        notToPromote = buildResult.find { openstackrelease, result -> result != 'SUCCESS' }
//        buildResult.each { openstackrelease, result -> println("${openstackrelease}: ${result}") }
    }

    stage('Promotion to testing repo'){
        if (notToPromote) {
            echo 'Snapshot can not be promoted!!!'
            currentBuild.result = 'FAILURE'
        }
    }
}

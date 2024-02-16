@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7.1')

import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*


def printUsage(){
  println """Usage:
  groovy nexusSync.groovy [type] [sourceUrl] [toUrl] [TargetNexusID] [TargetNexusPass]

  type:      		maven or npm
  sourceUrl: 		sync source. must end with '/'
  toUrl:     		sync target. must end with '/'
  TargetNexusID:	target Nexus ID Default admin. Optional
  TargetNexusPass:	target Nexus ID Default admin. Optional

Example:
  groovy nexusSync.groovy maven http://localhost:8081/nexus/maven-public/ http://my-private-nexus.com/nexus/private_repository/ admin password
  
"""
}

if (args.length != 5){
  printUsage();
  return;
}

def type = args[0];
if ('maven' != type && 'npm' != type && 'yum' != type){
  printUsage();
  return;
}


def isValidUrl(url) {
  return url.startsWith('http') && url.endsWith('/');
}

def sourceFullUrl = args[1];
def targetFullUrl = args[2];
def nexusID = args [3];
def nexusPass = args [4];

if (nexusID) {
  println "Target Nexus ID : ${nexusID}"
} 
if (nexusPass) {
  println "Target Nexus Password : ${nexusPass}"
} 

if (sourceFullUrl == targetFullUrl || ! isValidUrl(sourceFullUrl) || ! isValidUrl(targetFullUrl)){
  println "Invalid url"
  printUsage();
  return;
}

def sourceRepository = sourceFullUrl.split('/')[-1]
def targetRepository = targetFullUrl.split('/')[-1]

def leftUrl = sourceFullUrl[0..(sourceFullUrl.lastIndexOf(sourceRepository)-1)]
def rightUrl = targetFullUrl[0..(targetFullUrl.lastIndexOf(targetRepository)-1)]

println "Source Url: ${leftUrl}${sourceRepository}"
println "Target Url: ${rightUrl}${targetRepository}"

def repositories = [
	[from:sourceRepository, to:targetRepository, type: type],
]


def fetch = { url, repository->

	def context = url.substring(url.lastIndexOf('/'))

	def restClient = new RESTClient(url)
	restClient.ignoreSSLIssues()

	def continuationToken = null ;
	def items = []
	def path = "${context}/service/rest/v1/assets".replace('//','/').replace('//','/').replace('//','/')

	while(true) {
		def query = ['repository':repository]

		if (continuationToken!=null) {
			query['continuationToken'] = continuationToken;
		}
		println query
		def response = restClient.get(
			path: path ,
			contentType: JSON,
			query:query
		)


		items.addAll(response.data.items)
		continuationToken = response.data['continuationToken']
		if (continuationToken == null) {
			break;
		}

	}
	return items;
}




def convert = {
	return [
			'path':it.path,
			'downloadUrl':it.downloadUrl,
			'checksum': it.checksum.sha1
		]}

def fails  = []
repositories.each { repository ->
	def lefts = fetch(leftUrl, repository.from).collect(convert)
	println lefts
	def rights = fetch(rightUrl, repository.to) .collect(convert)
	println rights
	def remains = []
	lefts.findAll { leftItem ->
		def matched = rights.find { rightItem ->
			return leftItem.path == rightItem.path && leftItem.checksum == rightItem.checksum
		}
		if (matched == null){
			remains += leftItem;
		}

	}


	remains.each {
	
		println "Mismatched: ${it}"

		def fileName =  "${it.downloadUrl.substring(it.downloadUrl.lastIndexOf('/')+1)}";
		def downloadCmd = "curl --insecure ${it.downloadUrl} -s --retry 12 --retry-connrefused --output ${it.downloadUrl.substring(it.downloadUrl.lastIndexOf('/')+1)}"
		
		if (repository.type == 'maven' || repository.type == 'yum'){
			println downloadCmd
			println downloadCmd.execute().text

			if (new File(fileName).exists()==false || new File(fileName).length() ==0){
				println "!! Fail to download ${fileName}"
				fails += fileName
				return;
			}

			def leftPath = "/repository/${repository.from}/"
			def subpath = it.downloadUrl.substring(it.downloadUrl.indexOf(leftPath)+leftPath.length());

			def uploadUrl = "${rightUrl}${repository.to}/${subpath}"
			def uploadCmd = "curl --insecure -s -u ${nexusID}:${nexusPass} --retry 12 --retry-connrefused --upload-file ${fileName} ${uploadUrl}"
			println uploadCmd
			println uploadCmd.execute().text
			
			def rmCmd = "rm -f ${fileName}"
			println rmCmd
			println rmCmd.execute().text
		}
		else if (repository.type == 'npm' && fileName.endsWith("gz")){
			println downloadCmd
			println downloadCmd.execute().text

			if (new File(fileName).exists()==false || new File(fileName).length() ==0){
				println "!! Fail to download ${fileName}"
				fails += fileName
				return;
			}

			def uploadUrl = "${rightUrl}${repository.to}/"
			def uploadCmd = "npm --registry ${uploadUrl} publish ${fileName}"
			println uploadCmd
			println uploadCmd.execute().text
			
			def rmCmd = "rm -f ${fileName}"
			println rmCmd
			println rmCmd.execute().text
		}


	}


}
println "-----------------------------------"
println "Failed List"
println "-----------------------------------"
fails.each { println it}

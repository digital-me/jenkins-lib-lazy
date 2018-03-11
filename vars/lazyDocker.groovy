#!groovy

/*
 * This work is protected under copyright law in the Kingdom of
 * The Netherlands. The rules of the Berne Convention for the
 * Protection of Literary and Artistic Works apply.
 * Digital Me B.V. is the copyright owner.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Function to copy Dockerfile from lib to workspace if needed and build the image
def buildImage(stage, dist, args = '', filename = 'Dockerfile') {
	// Retrieving global config
	def config = lazyConfig()

	def dstDockerfile = "./${stage}/${filename}"

	// Enter sub-folder where Dockerfiles and scripts are located
	dir(config.sdir) {
		// Lookup fo the relevant Dockerfile in sub workspace first
		def srcDockerfile = sh(
			returnStdout: true,
			script: "ls -1 ${stage}/${dist}.Dockerfile 2> /dev/null || ls -1 ${dist}.Dockerfile 2> /dev/null || echo"
		).trim()

		def contentDockerfile = ''
		if (srcDockerfile != null && srcDockerfile != '') {
			// Read Dockerfile from workspace if existing
			contentDockerfile = readFile(srcDockerfile)
		} else {
			// Extract Dockerfile from shared lib
			try {
				contentDockerfile = libraryResource("${config.sdir}/${stage}/${dist}.Dockerfile")
			} catch (hudson.AbortException e) {
				contentDockerfile = libraryResource("${config.sdir}/${dist}.Dockerfile")
			}
		}

		// Write the selected Dockerfile to workspace sub-folder
		writeFile(
			file: dstDockerfile,
			text: contentDockerfile
		)
	}

	// Get uid of current UID and GID to build docker image
	// This will allow Jenkins to manipulate content generated within Docker
	def uid = sh(returnStdout: true, script: 'id -u').trim()
	def gid = sh(returnStdout: true, script: 'id -g').trim()
	
	ansiColor('xterm') {
		withEnv(["UID=${uid}", "GID=${gid}"]) {
			return docker.build(
				"${config.name}-${stage}-${dist}:${config.branch}",
				"--build-arg dir=${stage} --build-arg uid=${env.UID} --build-arg gid=${env.GID} -f ${config.sdir}/${dstDockerfile} ${config.sdir}"
			)
		}
	}
}

def call (stage, task, dist, args = '') {
	// Retrieving global config
	def config = lazyConfig()

	if (config.verbose) echo "Docker step for stage ${stage} inside ${dist}: started"

	// Execute pre closure first
	if (task.preout) task.preout.call()

	// Prepare steps without executing
	def steps = lazyStep(stage, task.exec, dist)
	
	// Build the relevant Docker image
	def imgDocker = buildImage(stage, dist)

	// Run each shell scripts as task inside the Docker
	imgDocker.inside(args) {
		ansiColor('xterm') {
			withEnv(["DIST=${dist}"]) {
				// Execut each step
				steps.each { step ->
					step()
				}
			}
		}
	}

	// Execute post closure at the end
	if (task.postout) task.postout.call()

	if (config.verbose) echo "Docker step for stage ${stage} inside ${dist}: finished"
}

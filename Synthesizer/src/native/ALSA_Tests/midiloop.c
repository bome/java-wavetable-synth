/*
 * (C) Copyright IBM Corp. 2005, 2008
 * All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * Test program: echos MIDI IN to MIDI OUT
 * based on test program in ALSA workspace.
 */

#include <stdlib.h>
#include <stdio.h>
#include <ctype.h>
#include <sys/time.h>
#include <alsa/asoundlib.h>
#include <string.h>
#include <signal.h>
#include <poll.h>

#define POLL_DESCRIPTOR_COUNT 5

/* #define STEP_THROUGH
*/

static void usage(void)
{
	fprintf(stderr, "Usage: midiloop [options] in_dev out_dev\n");
	fprintf(stderr, "  options:\n");
	fprintf(stderr, "    -v: verbose mode\n");
	fprintf(stderr, "    -t: print time for every incoming byte\n");
	fprintf(stderr, "    -i: MIDI input only\n");
	fprintf(stderr, "    -b: use blocking read instead of poll()\n");
	fprintf(stderr, "    in_dev : test rawmidi input device\n");
	fprintf(stderr, "    out_dev: test rawmidi input device\n");
}

void key(char* s) {
#ifdef STEP_THROUGH
	char c;
	printf("%s  [ENTER]\n", s);
	getc(stdin);
#endif
}

int stop = 0;
int use_poll = 1;

void sighandler(int dummy ATTRIBUTE_UNUSED)
{
	stop=1;
	if (!use_poll) {
		printf("Press a MIDI key now (to bail out of the blocking read call).\n");
	}
}

static int startTime = 0;

int getTimeInMilliseconds() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    int currTime = (tv.tv_sec * 1000) + (tv.tv_usec / 1000L);
    if (startTime == 0) {
      startTime = currTime;
    }
    return currTime - startTime;
}

int main(int argc, char** argv)
{
	int i;
	int err;
	int verbose = 0;
	int timing = 0;
	int inputOnly = 0;
	int count = 0;
	snd_rawmidi_t *handle_in = NULL, *handle_out = NULL;
	unsigned char buf;
	char *iname, *oname;
	snd_rawmidi_status_t *istat, *ostat;
	struct pollfd poll_descriptors[POLL_DESCRIPTOR_COUNT];
	int poll_descriptor_count = 0;
	int last_param = 0;

	for (i = 1 ; i<argc ; i++) {
		if (argv[i][0]=='-') {
			if (strcmp(argv[i], "--help") == 0) {
				usage();
				return 0;
			}
			last_param = i;
			switch (argv[i][1]) {
				case 'h':
					usage();
					return 0;
				case 'v':
					verbose = 1;
					break;
				case 't':
					timing = 1;
					break;
				case 'i':
					inputOnly = 1;
					break;
				case 'b':
					use_poll = 0;
					break;
				default:
					if (i < argc-2) {
						fprintf(stderr, "unknown parameter: %s\n", argv[i]);
						usage();
						return 1;
					}
					break;
			}
		} else break;
	}
	if (last_param >= argc - 2 + inputOnly) {
		fprintf(stderr, "too few parameters\n");
		usage();
		return 1;
	}

	iname = argv[argc-2 + inputOnly];
	oname = argv[argc-1];

	if (verbose) {
		fprintf(stderr, "Using: \n");
		if (inputOnly) {
		  fprintf(stderr, "  Input: %s\n", iname);
		} else {
		  fprintf(stderr, "  Input: %s  Output: %s\n", iname, oname);
		}
	}

	/* INPUT */
	key("before open input");
	err = snd_rawmidi_open(&handle_in, NULL, iname, SND_RAWMIDI_NONBLOCK);
	if (err) {
		fprintf(stderr,"snd_rawmidi_open %s failed: %d\n",iname,err);
		exit(EXIT_FAILURE);
	}
	if (timing) {
	  /* initialize time to 0 */
	  getTimeInMilliseconds();
	}
	snd_rawmidi_nonblock(handle_in, use_poll?1:0);
	if (use_poll) {
		poll_descriptor_count = snd_rawmidi_poll_descriptors(handle_in, poll_descriptors, POLL_DESCRIPTOR_COUNT);
		if (poll_descriptor_count <= 0) {
			fprintf(stderr, "ERROR: snd_rawmidi_poll_descriptors returned %d poll descriptors",
						   poll_descriptor_count);
			exit(EXIT_FAILURE);
		}
	}

	/* OUTPUT */
	if (!inputOnly) {
	  key("before open output");
	  err = snd_rawmidi_open(NULL, &handle_out, oname, 0);
	  if (err) {
	    fprintf(stderr,"snd_rawmidi_open %s failed: %d\n",oname,err);
	    exit(EXIT_FAILURE);
	  }
	  snd_rawmidi_nonblock(handle_out, 0);
	}
	signal(SIGINT, sighandler);

	if (use_poll) {
		printf("Press Ctrl-C to quit.\n");
	} else {
		printf("After Ctrl-C, press a MIDI key to quit.\n");
	}

	

	/* do the loop */
	while (!stop) {
		key("before read");
		i = snd_rawmidi_read(handle_in, &buf, 1);
		if (stop) break;
		if (i == 1) {
		  if (handle_out != NULL) {
		    i = snd_rawmidi_write(handle_out, &buf, 1);
		    if (i != 1) {
		      fprintf(stderr, "Error writing byte to MIDI OUT\n");
		    }
		  }
		  count++;
		  if (timing) {
		    printf("%dms: received byte %d: %2x\n", getTimeInMilliseconds(), count, buf);
		    fflush(stdout);
		  } else {
			if (verbose && (buf & 0x80)) {
				printf(".");
				fflush(stdout);
			}
		  }
		} else if (use_poll) {
			/* wait for an event to arrive */
			poll(poll_descriptors, poll_descriptor_count, 200);
			if (stop) break;
		}
	}

	if (verbose) {
		printf("\nEnd...\n");

		snd_rawmidi_status_alloca(&istat);
		err = snd_rawmidi_status(handle_in, istat);
		if (err < 0)
			fprintf(stderr, "input stream status error: %d\n", err);
		printf("input.status.avail = %i\n", snd_rawmidi_status_get_avail(istat));
		printf("input.status.xruns = %i\n", snd_rawmidi_status_get_xruns(istat));

		if (handle_out != NULL) {
		  snd_rawmidi_status_alloca(&ostat);
		  err = snd_rawmidi_status(handle_out, ostat);
		  if (err < 0)
		    fprintf(stderr, "output stream status error: %d\n", err);
		  printf("output.status.avail = %i\n", snd_rawmidi_status_get_avail(ostat));
		  printf("output.status.xruns = %i\n", snd_rawmidi_status_get_xruns(ostat));
		}
	}
	printf("received bytes:       %d\n", count);

	if (verbose) {
		fprintf(stderr,"Closing\n");
	}
	snd_rawmidi_drain(handle_in);
	snd_rawmidi_close(handle_in);
	if (handle_out != NULL) {
	  snd_rawmidi_drain(handle_out);
	  snd_rawmidi_close(handle_out);
	}

	return 0;
}

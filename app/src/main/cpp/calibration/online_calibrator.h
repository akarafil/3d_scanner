#ifndef ONLINE_CALIBRATOR_H
#define ONLINE_CALIBRATOR_H

#if __has_include(<opencv2/opencv.hpp>)
#include <opencv2/opencv.hpp>
#include <opencv2/calib3d.hpp>
#ifndef HAVE_OPENCV
#define HAVE_OPENCV 1
#endif
#endif

#if HAVE_OPENCV
class OnlineHingeCalibrator {
public:
    bool CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out);
};
#else
namespace cv {
    class Mat {
    public:
        bool empty() const { return true; }
    };
}

class OnlineHingeCalibrator {
public:
    bool CalibrateExtrinsics(const cv::Mat& imgWide, const cv::Mat& imgUltraWide, cv::Mat& R_out, cv::Mat& T_out);
};
#endif

#endif // ONLINE_CALIBRATOR_H
